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

internal class OperationBinding(
    val scope: Scope,
    val invoke: suspend (codec: KoraCodec, context: CallContext, payload: ByteArray) -> ByteArray,
)

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
 * A capability is an atomic contract: a provider must register a handler for **every** operation and a source for
 * **every** event the capability declares, otherwise `KoraNode.provide` throws `IllegalArgumentException` naming
 * the missing members. Advertising `media.library@1.0` promises all of it, so peers, the gate and MCP never see
 * a member that then fails. Features a provider may or may not have belong in separate capabilities.
 */
public class ProviderBuilder<C : Capability> internal constructor(public val capability: C) {
    private val operations = mutableMapOf<String, OperationBinding>()
    private val events = mutableMapOf<String, EventBinding>()

    /**
     * Handles [spec]. The handler runs in the calling coroutine, with [CallContext] as receiver so it can read
     * `caller`. Throw `KoraException` to fail with a specific error; anything else becomes `internal`.
     */
    public fun <I, O> on(spec: OperationSpec<I, O>, handler: suspend CallContext.(input: I) -> O) {
        requireOwn(spec.capabilityId)
        require(spec.name !in operations) { "Operation '${spec.name}' is already handled" }
        operations[spec.name] = OperationBinding(spec.scope) { codec, context, payload ->
            val input = try {
                spec.decodeInput(codec, payload)
            } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
                throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed input for '${spec.name}'"), e)
            }
            spec.encodeOutput(codec, context.handler(input))
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
