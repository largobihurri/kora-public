package eus.bihurri.kora.android

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.android.ipc.IKoraStreamCallback
import eus.bihurri.kora.android.ipc.IKoraSubscription
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.StreamedResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Port: notices when the process behind a sink dies, so its subscription can be dropped. */
internal fun interface SinkWatcher {
    /** Calls [onDeath] when [sink]'s process dies. Returns null if it is already dead. */
    fun watch(sink: IKoraEventSink, onDeath: () -> Unit): AutoCloseable?
}

/** [SinkWatcher] over `IBinder.linkToDeath`. */
internal object BinderSinkWatcher : SinkWatcher {
    override fun watch(sink: IKoraEventSink, onDeath: () -> Unit): AutoCloseable? {
        val binder: IBinder = sink.asBinder() ?: return null
        val recipient = IBinder.DeathRecipient { onDeath() }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            return null
        }
        return AutoCloseable {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: NoSuchElementException) {
                // Already unlinked because the binder died.
            }
        }
    }
}

/**
 * The server side of the Binder interface, free of Android service plumbing so it can be unit tested.
 *
 * Every entry point takes the calling uid of its transaction and identifies the caller through [callers]; the
 * request is then served by [node] as that caller. Calls and subscriptions run on [scope], so no Binder thread
 * waits for a suspending handler: results go back through the callbacks.
 *
 * Outgoing envelopes above [maxEnvelopeBytes] are replaced by `payload_too_large` failures (see [PayloadLimits]).
 *
 * Byte streams (protocol version 2): [callSending] reads an upload from the descriptor the caller sent and
 * [callReceiving] answers a download with the read end of a pipe it pumps the provider's bytes into, on
 * [pumpContext]. The node runs lookup, policy, version check and audit on the envelope before a handler can read a
 * byte; nothing here reads one first. A stream that stalls for [streamIdleTimeout] is ended: a handler blocked on a
 * read gets `timeout`, and a pump the caller stopped reading is stopped. A pump whose provider stalls is stopped after
 * twice that, so that the caller's read times out first. Every descriptor the server holds is closed on every path.
 */
internal class NodeServer(
    private val node: KoraNode,
    private val callers: CallerResolver,
    private val scope: CoroutineScope,
    private val sinks: SinkWatcher = BinderSinkWatcher,
    private val codec: KoraCodec = CborCodec,
    private val maxEnvelopeBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
    internal val streamIdleTimeout: Duration = DEFAULT_STREAM_IDLE_TIMEOUT,
    private val descriptors: Descriptors = AndroidDescriptors,
    private val pumpContext: CoroutineContext = Dispatchers.IO,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val registry = SubscriptionRegistry()

    /** Number of subscriptions currently served. */
    val activeSubscriptions: Int get() = registry.size

    /**
     * The descriptor of the node, for any identified caller, including external apps and the shell. It lists
     * capability ids and versions only (no user data) and is what a caller needs to know before asking the user
     * for access; every operation and event still goes through the caller policy.
     */
    fun describe(callingUid: Int): ByteArray {
        callers.resolve(callingUid) ?: throw SecurityException("Caller is not allowed to use this Kora node")
        return codec.encode(NodeDescriptor.serializer(), node.describe())
    }

    fun call(callingUid: Int, request: ByteArray, callback: IKoraCallback) {
        val caller = callers.resolve(callingUid)
        if (caller == null) {
            respond(callback, failure(requestId(request), ErrorCode.UNAUTHORIZED, "Caller is not allowed to use this Kora node"))
            return
        }
        if (request.size > maxEnvelopeBytes) {
            respond(callback, failure("", ErrorCode.PAYLOAD_TOO_LARGE, "Request of ${request.size} bytes exceeds $maxEnvelopeBytes"))
            return
        }
        scope.launch {
            val decoded = try {
                codec.decode(KoraRequest.serializer(), request)
            } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
                respond(callback, failure("", ErrorCode.INVALID_PAYLOAD, "Malformed request"))
                return@launch
            }
            respond(callback, node.handle(caller, decoded))
        }
    }

    /**
     * Serves an upload: [request] arrives with [bytes], a descriptor that carries [sizeBytes] bytes, which the server
     * owns from here and closes on every path. A caller that cannot be identified is answered `unauthorized`, as in
     * [call]; an envelope over the limit `payload_too_large`; a missing descriptor or a negative size
     * `invalid_payload`. Otherwise the node serves the request, on [scope], with the bytes as a `ByteStream` whose
     * reads fail with `timeout` when one waits [streamIdleTimeout] for a byte; bytes that do not carry [sizeBytes]
     * answer `invalid_payload` when that failure escapes the handler. The watched input is closed when the node
     * answers, whether or not the handler read it.
     *
     * The caller's side counts [streamIdleTimeout] from its last byte until the answer arrives, so a handler starts
     * reading promptly (the caller's pump fills the pipe and then waits) and answers soon after the last byte.
     */
    fun callSending(callingUid: Int, request: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long, callback: IKoraCallback) {
        val caller = callers.resolve(callingUid)
            ?: return refuse(callback, bytes, failure(requestId(request), ErrorCode.UNAUTHORIZED, "Caller is not allowed to use this Kora node"))
        if (request.size > maxEnvelopeBytes) {
            return refuse(callback, bytes, failure("", ErrorCode.PAYLOAD_TOO_LARGE, "Request of ${request.size} bytes exceeds $maxEnvelopeBytes"))
        }
        if (bytes == null || sizeBytes < 0) {
            val message = "A stream call needs its byte stream and a size that is not negative"
            return refuse(callback, bytes, failure(requestId(request), ErrorCode.INVALID_PAYLOAD, message))
        }
        scope.launch {
            val decoded = try {
                codec.decode(KoraRequest.serializer(), request)
            } catch (e: IllegalArgumentException) {
                respond(callback, failure("", ErrorCode.INVALID_PAYLOAD, "Malformed request"))
                return@launch
            }
            val input = WatchedInput(descriptors.input(bytes), streamIdleTimeout, scope, timeSource)
            val response = try {
                node.handleSending(caller, decoded, ByteStream(sizeBytes, input))
            } finally {
                input.closeQuietly() // ends its watchdog; core closes the bytes too, but this side does not rely on it
            }
            respond(callback, response)
        }.invokeOnCompletion { bytes.closeQuietly() } // on every path, also when the scope is already gone
    }

    /**
     * Serves a download: the node's answer goes back through [callback], a success with the read end of a pipe and
     * the size of its bytes, a failure with no descriptor. Refusals are those of [call]. The provider's bytes are
     * pumped into the pipe off the Binder thread; when the caller stops reading for [streamIdleTimeout], or the
     * provider's bytes stall for twice that, the pump is stopped and both ends it holds are closed.
     */
    fun callReceiving(callingUid: Int, request: ByteArray, callback: IKoraStreamCallback) {
        val caller = callers.resolve(callingUid)
        if (caller == null) {
            respond(callback, failure(requestId(request), ErrorCode.UNAUTHORIZED, "Caller is not allowed to use this Kora node"))
            return
        }
        if (request.size > maxEnvelopeBytes) {
            respond(callback, failure("", ErrorCode.PAYLOAD_TOO_LARGE, "Request of ${request.size} bytes exceeds $maxEnvelopeBytes"))
            return
        }
        scope.launch {
            val decoded = try {
                codec.decode(KoraRequest.serializer(), request)
            } catch (e: IllegalArgumentException) {
                respond(callback, failure("", ErrorCode.INVALID_PAYLOAD, "Malformed request"))
                return@launch
            }
            deliver(callback, node.handleReceiving(caller, decoded))
        }
    }

    /** Answers an upload that is not served with [response], closing its descriptor first. */
    private fun refuse(callback: IKoraCallback, bytes: ParcelFileDescriptor?, response: KoraResponse) {
        bytes?.closeQuietly()
        respond(callback, response)
    }

    /** Sends [answer] to [callback]: a failure as it is, a success with a pipe its bytes are then pumped into. */
    private fun deliver(callback: IKoraStreamCallback, answer: StreamedResponse) {
        val bytes = answer.bytes ?: return respond(callback, answer.response)
        var pumping = false
        try {
            val encoded = codec.encode(KoraResponse.serializer(), answer.response)
            if (encoded.size > maxEnvelopeBytes) {
                respond(callback, failure(answer.response.id, ErrorCode.PAYLOAD_TOO_LARGE, "Response of ${encoded.size} bytes exceeds $maxEnvelopeBytes"))
                return
            }
            val pipe = try {
                descriptors.createPipe()
            } catch (e: IOException) {
                Log.w(TAG, "Cannot open a pipe for a byte stream", e)
                respond(callback, failure(answer.response.id, ErrorCode.INTERNAL, "Internal error"))
                return
            }
            val sink = descriptors.output(pipe.writeEnd)
            try {
                callback.onResponse(encoded, pipe.readEnd, bytes.sizeBytes)
            } catch (e: Exception) { // RemoteException: the caller went away, so nobody reads
                Log.d(TAG, "Caller went away before the byte stream was delivered", e)
                sink.closeQuietly()
                return
            } finally {
                pipe.readEnd.closeQuietly() // the caller holds its own copy now
            }
            pumpDownload(bytes, sink)
            pumping = true
        } finally {
            if (!pumping) bytes.closeQuietly()
        }
    }

    /**
     * Pumps [bytes] into [sink] on [pumpContext]. A watchdog on [scope] stops it, by closing both (that is what
     * unblocks a pump waiting on either), when [scope] ends and when no byte moves for too long: [streamIdleTimeout]
     * while the pump waits for room (the caller stopped reading), [PROVIDER_STALL_FACTOR] times that while it waits
     * for the provider's bytes. A stalled provider is given longer because the caller's read, which only starts
     * waiting once it has drained the pipe, must time out first: it then fails with `timeout`, where a pipe closed
     * by this side would read as a stream cut short.
     */
    private fun pumpDownload(bytes: ByteStream, sink: OutputStream) {
        val watchdog = IdleWatchdog(streamIdleTimeout, timeSource).apply { startWaiting() }
        val guard = scope.launch {
            watchdog.awaitIdle()
            Log.d(TAG, "A byte stream stalled; dropping it")
        }
        guard.invokeOnCompletion {
            sink.closeQuietly()
            bytes.closeQuietly()
        }
        scope.launch(pumpContext) {
            val result = pump(bytes.input, bytes.sizeBytes, sink, watchdog, sourceTimeout = streamIdleTimeout * PROVIDER_STALL_FACTOR)
            if (result !is PumpResult.Done) Log.d(TAG, "A byte stream ended early: $result")
        }.invokeOnCompletion { guard.cancel() }
    }

    fun subscribe(callingUid: Int, request: ByteArray, sink: IKoraEventSink): IKoraSubscription {
        val caller = callers.resolve(callingUid)
        if (caller == null) {
            fail(sink, ErrorCode.UNAUTHORIZED, "Caller is not allowed to use this Kora node")
            return Inactive
        }
        if (request.size > maxEnvelopeBytes) {
            fail(sink, ErrorCode.PAYLOAD_TOO_LARGE, "Request of ${request.size} bytes exceeds $maxEnvelopeBytes")
            return Inactive
        }
        val decoded = try {
            codec.decode(SubscribeRequest.serializer(), request)
        } catch (e: IllegalArgumentException) {
            fail(sink, ErrorCode.INVALID_PAYLOAD, "Malformed subscription request")
            return Inactive
        }

        val subscription = ServerSubscription(caller.kind) {
            fail(sink, ErrorCode.UNAUTHORIZED, "Access was revoked")
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { pump(caller, decoded, sink) }
        subscription.attach(job)
        registry.add(subscription)
        val watch = sinks.watch(sink) { job.cancel() }
        if (watch == null) job.cancel() // the subscriber is already gone
        job.invokeOnCompletion {
            watch?.close()
            registry.remove(subscription)
        }
        job.start()
        return object : IKoraSubscription.Stub() {
            override fun cancel() = subscription.cancel()
        }
    }

    /**
     * Cancels every live subscription whose caller is neither [CallerKind.SELF] nor [CallerKind.PEER] (external
     * apps, the shell, remote agents), telling its sink `unauthorized`. The app calls it, through
     * `KoraAndroid.revokeUntrustedSubscriptions`, when the user turns the master switch off or removes a grant: the
     * policy is only consulted when a subscription opens, so a live one keeps flowing until it is revoked.
     * Returns how many subscriptions were cancelled.
     */
    fun revokeUntrusted(): Int {
        val revoked = registry.removeWhere { it.kind != CallerKind.SELF && it.kind != CallerKind.PEER }
        revoked.forEach { it.revoke() }
        return revoked.size
    }

    private suspend fun pump(caller: Caller, request: SubscribeRequest, sink: IKoraEventSink) {
        try {
            node.subscribe(caller, request).collect { event ->
                val bytes = codec.encode(KoraEvent.serializer(), event)
                if (bytes.size > maxEnvelopeBytes) {
                    throw KoraException(
                        KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "Event of ${bytes.size} bytes exceeds $maxEnvelopeBytes"),
                    )
                }
                try {
                    sink.onEvent(bytes)
                } catch (e: RemoteException) {
                    throw CancellationException("Subscriber is gone", e)
                }
            }
            try {
                sink.onComplete()
            } catch (_: RemoteException) {
                // Subscriber is gone; nothing left to tell.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoraException) {
            fail(sink, e.error.code, e.error.message)
        } catch (e: Exception) {
            Log.w(TAG, "Subscription failed", e)
            fail(sink, ErrorCode.INTERNAL, "Internal error")
        }
    }

    private fun respond(callback: IKoraCallback, response: KoraResponse) {
        var bytes = codec.encode(KoraResponse.serializer(), response)
        if (bytes.size > maxEnvelopeBytes) {
            val tooLarge = failure(response.id, ErrorCode.PAYLOAD_TOO_LARGE, "Response of ${bytes.size} bytes exceeds $maxEnvelopeBytes")
            bytes = codec.encode(KoraResponse.serializer(), tooLarge)
        }
        try {
            callback.onResponse(bytes)
        } catch (e: RemoteException) {
            Log.d(TAG, "Caller went away before the response was delivered", e)
        }
    }

    /** Answers a download that carries no bytes; [response] is a failure. Envelopes over the limit as in [respond]. */
    private fun respond(callback: IKoraStreamCallback, response: KoraResponse) {
        var bytes = codec.encode(KoraResponse.serializer(), response)
        if (bytes.size > maxEnvelopeBytes) {
            val tooLarge = failure(response.id, ErrorCode.PAYLOAD_TOO_LARGE, "Response of ${bytes.size} bytes exceeds $maxEnvelopeBytes")
            bytes = codec.encode(KoraResponse.serializer(), tooLarge)
        }
        try {
            callback.onResponse(bytes, null, 0)
        } catch (e: RemoteException) {
            Log.d(TAG, "Caller went away before the response was delivered", e)
        }
    }

    private fun fail(sink: IKoraEventSink, code: ErrorCode, message: String) {
        try {
            sink.onError(codec.encode(KoraError.serializer(), KoraError(code, message)))
        } catch (e: RemoteException) {
            Log.d(TAG, "Subscriber went away before the error was delivered", e)
        }
    }

    private fun failure(id: String, code: ErrorCode, message: String) = KoraResponse.Failure(id, KoraError(code, message))

    /** Best-effort id of a request that is being refused, so the caller can match the answer. */
    private fun requestId(request: ByteArray): String =
        if (request.size > maxEnvelopeBytes) "" else try {
            codec.decode(KoraRequest.serializer(), request).id
        } catch (e: IllegalArgumentException) {
            ""
        }

    private object Inactive : IKoraSubscription.Stub() {
        override fun cancel() = Unit
    }

    private companion object {
        const val TAG = "KoraAndroid"

        /** How many times [streamIdleTimeout] a download pump waits for a stalled provider (see [pumpDownload]). */
        const val PROVIDER_STALL_FACTOR = 2
    }
}
