package eus.bihurri.kora.android

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
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
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

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
 */
internal class NodeServer(
    private val node: KoraNode,
    private val callers: CallerResolver,
    private val scope: CoroutineScope,
    private val sinks: SinkWatcher = BinderSinkWatcher,
    private val codec: KoraCodec = CborCodec,
    private val maxEnvelopeBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
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
    }
}
