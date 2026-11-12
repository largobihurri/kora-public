package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.StreamDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What a provider callback knows about the access it is serving. */
public class CallContext internal constructor(
    /** Who is calling, as verified by the transport. */
    public val caller: Caller,
    public val capability: CapabilityId,
    /** Operation name for calls, event name for subscriptions. */
    public val member: String,
)

/** How a node serves one operation: one kind per [StreamDirection]. */
internal sealed interface OperationBinding {
    val scope: Scope
}

/** An operation without a stream ([StreamDirection.NONE]): payload in, payload out. */
internal class PlainBinding(
    override val scope: Scope,
    val invoke: suspend (codec: KoraCodec, context: CallContext, payload: ByteArray) -> ByteArray,
) : OperationBinding

/** An [StreamDirection.UPLOAD] operation: the handler also reads the bytes; the node closes them afterwards. */
internal class UploadBinding(
    override val scope: Scope,
    val invoke: suspend (codec: KoraCodec, context: CallContext, payload: ByteArray, bytes: ByteStream) -> ByteArray,
) : OperationBinding

/** A [StreamDirection.DOWNLOAD] operation: the encoded output and the bytes, which the receiver owns. */
internal class DownloadBinding(
    override val scope: Scope,
    val invoke: suspend (codec: KoraCodec, context: CallContext, payload: ByteArray) -> WithBytes<ByteArray>,
) : OperationBinding

internal class EventBinding(
    val scope: Scope,
    val open: (codec: KoraCodec, context: CallContext) -> Flow<ByteArray>,
)

/** The handlers a node registered for one [capability]. */
internal class CapabilityProvider(
    val capability: Capability,
    val operations: Map<String, OperationBinding>,
    val events: Map<String, EventBinding>,
)

/**
 * Registration DSL for the provider side of one capability, used through `KoraNode.provide`.
 *
 * A capability is an atomic contract: a provider must register a handler for **every** operation (with [on],
 * [onUpload] or [onDownload], by the operation's stream) and a source for **every** event the capability declares,
 * otherwise `KoraNode.provide` throws `IllegalArgumentException` naming the missing members. Advertising
 * `media.library@1.0` promises all of it, so peers, the gate and MCP never see a member that then fails. Features a
 * provider may or may not have belong in separate capabilities.
 */
public class ProviderBuilder<C : Capability> internal constructor(public val capability: C) {
    private val operations = mutableMapOf<String, OperationBinding>()
    private val events = mutableMapOf<String, EventBinding>()

    /**
     * Handles [spec]. The handler runs in the calling coroutine, with [CallContext] as receiver so it can read
     * `caller`. Throw `KoraException` to fail with a specific error; anything else becomes `internal`.
     *
     * [spec] must carry no stream: an upload operation is registered with [onUpload] and a download operation with
     * [onDownload]; any other combination throws `IllegalArgumentException`.
     */
    public fun <I, O> on(spec: OperationSpec<I, O>, handler: suspend CallContext.(input: I) -> O) {
        requireRegistrable(spec, StreamDirection.NONE)
        operations[spec.name] = PlainBinding(spec.scope) { codec, context, payload ->
            spec.encodeOutput(codec, context.handler(decodeInput(spec, codec, payload)))
        }
    }

    /**
     * Handles [spec], an operation whose stream is [StreamDirection.UPLOAD]: the handler receives the typed input
     * and the [ByteStream] the caller sent, whose `input` reads exactly the declared size. The node has already run
     * the policy and the audit on the envelope, so no byte is read for a refused caller; it closes the bytes once
     * the handler returns or fails, whether or not they were read. Errors as in [on], with one exception: a
     * [ByteStreamSizeException] of these bytes (they ended short or ran over) that escapes the handler answers
     * `invalid_payload`, since the caller sent a malformed stream, and is not reported as an internal error.
     *
     * The answer must arrive within the caller's stream idle timeout of the last byte, so an upload only stages what
     * it receives and leaves slow work for later. Start reading promptly: the sender fills the pipe and then waits.
     *
     * Reads block and the handler runs in the calling coroutine, so read off the caller's dispatcher, for example
     * in `withContext(Dispatchers.IO)`. A sender that sends more than the declared size is caught only by a handler
     * that reads `bytes.input` to its end (until it answers -1); one that stops after exactly the declared size
     * accepts the bytes without knowing that more followed.
     */
    public fun <I, O> onUpload(spec: OperationSpec<I, O>, handler: suspend CallContext.(input: I, bytes: ByteStream) -> O) {
        requireRegistrable(spec, StreamDirection.UPLOAD)
        operations[spec.name] = UploadBinding(spec.scope) { codec, context, payload, bytes ->
            spec.encodeOutput(codec, context.handler(decodeInput(spec, codec, payload), bytes))
        }
    }

    /**
     * Handles [spec], an operation whose stream is [StreamDirection.DOWNLOAD]: the handler answers the typed output
     * together with the [ByteStream] to send back. From then on the bytes belong to the receiver, which closes them;
     * if the answer cannot be delivered the node closes them. Errors as in [on].
     *
     * The handler runs in the calling coroutine, so blocking work to prepare the answer (opening a file, say) runs
     * off the caller's dispatcher, for example in `withContext(Dispatchers.IO)`; the bytes themselves are read later,
     * by the receiver. The declared size is checked against the source only as far as the receiver reads: a source
     * longer than declared is found only by a receiver that reads to the end.
     */
    public fun <I, O> onDownload(spec: OperationSpec<I, O>, handler: suspend CallContext.(input: I) -> WithBytes<O>) {
        requireRegistrable(spec, StreamDirection.DOWNLOAD)
        operations[spec.name] = DownloadBinding(spec.scope) { codec, context, payload ->
            val result = context.handler(decodeInput(spec, codec, payload))
            val output = try {
                spec.encodeOutput(codec, result.output)
            } catch (e: Throwable) {
                result.close()
                throw e
            }
            WithBytes(output, result.bytes)
        }
    }

    /**
     * Offers [spec] to subscribers. [source] is called once per subscription (with the subscriber as receiver) and
     * returns the flow of events for it; the subscription ends when the subscriber stops collecting.
     */
    public fun <E> emits(spec: EventSpec<E>, source: CallContext.() -> Flow<E>) {
        requireOwn(spec.capabilityId)
        require(spec.name !in events) { "Event '${spec.name}' is already offered" }
        events[spec.name] = EventBinding(spec.scope) { codec, context ->
            context.source().map { spec.encode(codec, it) }
        }
    }

    private fun requireOwn(owner: CapabilityId) {
        require(owner == capability.id) { "Spec of '$owner' cannot be registered in provider of '${capability.id}'" }
    }

    private fun requireRegistrable(spec: OperationSpec<*, *>, stream: StreamDirection) {
        requireOwn(spec.capabilityId)
        require(spec.stream == stream) {
            "Operation '${spec.name}' streams ${spec.stream}: register it with ${registration(spec.stream)}"
        }
        require(spec.name !in operations) { "Operation '${spec.name}' is already handled" }
    }

    private fun registration(stream: StreamDirection): String = when (stream) {
        StreamDirection.NONE -> "on"
        StreamDirection.UPLOAD -> "onUpload"
        StreamDirection.DOWNLOAD -> "onDownload"
    }

    private fun <I> decodeInput(spec: OperationSpec<I, *>, codec: KoraCodec, payload: ByteArray): I = try {
        spec.decodeInput(codec, payload)
    } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
        throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed input for '${spec.name}'"), e)
    }

    internal fun isHandled(operation: String): Boolean = operation in operations

    internal fun isOffered(event: String): Boolean = event in events

    internal fun registerOperation(name: String, binding: OperationBinding) {
        operations[name] = binding
    }

    internal fun registerEvent(name: String, binding: EventBinding) {
        events[name] = binding
    }

    /** Fails unless every operation and event declared by the capability is registered. */
    internal fun build(): CapabilityProvider {
        val missingOperations = capability.operations.map { it.name }.filter { it !in operations }
        val missingEvents = capability.events.map { it.name }.filter { it !in events }
        require(missingOperations.isEmpty() && missingEvents.isEmpty()) {
            buildString {
                append("Provider of '${capability.id}' must register every operation and event of the capability")
                if (missingOperations.isNotEmpty()) append("; missing operations: ${missingOperations.joinToString()}")
                if (missingEvents.isNotEmpty()) append("; missing events: ${missingEvents.joinToString()}")
            }
        }
        return CapabilityProvider(capability, operations.toMap(), events.toMap())
    }
}
