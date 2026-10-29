package eus.bihurri.kora.android

import android.os.RemoteException
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
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.PeerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * [PeerTransport] over Binder. A peer is addressed by its package name, which is its node id (see
 * [AndroidPeerDirectory]); connections come from the shared [pool] and are bound lazily.
 *
 * Failures reach the node as [KoraException]: `provider_unavailable` when the peer cannot be reached or dies,
 * `payload_too_large` for envelopes over [maxEnvelopeBytes], and whatever error the peer answered with.
 * Cancelling a call is safe: a late answer is dropped. Cancelling a subscription tells the peer to stop.
 *
 * @param binderContext where Binder calls that can block are made; the default keeps them off the caller thread.
 */
internal class BinderPeerTransport(
    private val pool: ConnectionPool,
    private val codec: KoraCodec = CborCodec,
    private val maxEnvelopeBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
    private val eventBuffer: Int = DEFAULT_EVENT_BUFFER,
    private val binderContext: CoroutineContext = Dispatchers.IO,
) : PeerTransport {

    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
        val bytes = encodeChecked(KoraRequest.serializer(), request)
        val lease = pool.acquire(peer.nodeId)
        try {
            val answer = CompletableDeferred<ByteArray>()
            val watch = lease.onLost { answer.completeExceptionally(unavailable("'${peer.nodeId}' disconnected")) }
            try {
                val callback = object : IKoraCallback.Stub() {
                    override fun onResponse(response: ByteArray) {
                        answer.complete(response)
                    }
                }
                try {
                    withContext(binderContext) { lease.node.call(bytes, callback) }
                } catch (e: RemoteException) {
                    throw unavailable("'${peer.nodeId}' is not reachable", e)
                }
                return decode(KoraResponse.serializer(), answer.await(), "response")
            } finally {
                watch.close()
            }
        } finally {
            lease.release()
        }
    }

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = callbackFlow {
        val bytes = encodeChecked(SubscribeRequest.serializer(), request)
        val lease = pool.acquire(peer.nodeId)
        val watch = lease.onLost { close(unavailable("'${peer.nodeId}' disconnected")) }
        val sink = object : IKoraEventSink.Stub() {
            override fun onEvent(event: ByteArray) {
                val decoded = try {
                    decode(KoraEvent.serializer(), event, "event")
                } catch (e: KoraException) {
                    close(e)
                    return
                }
                val sent = trySend(decoded)
                if (sent.isFailure && !sent.isClosed) {
                    close(KoraException(KoraError(ErrorCode.INTERNAL, "Consumer is too slow: event buffer is full")))
                }
            }

            override fun onError(error: ByteArray) {
                close(
                    try {
                        KoraException(decode(KoraError.serializer(), error, "error"))
                    } catch (e: KoraException) {
                        e
                    },
                )
            }

            override fun onComplete() {
                close()
            }
        }
        val handle: IKoraSubscription = try {
            withContext(binderContext) { lease.node.subscribe(bytes, sink) }
        } catch (e: Throwable) {
            watch.close()
            lease.release()
            throw if (e is RemoteException) unavailable("'${peer.nodeId}' is not reachable", e) else e
        }
        awaitClose {
            try {
                handle.cancel()
            } catch (_: RemoteException) {
                // The peer is gone, so the subscription is gone with it.
            }
            watch.close()
            lease.release()
        }
    }.buffer(eventBuffer)

    /** The node descriptor served by [packageName]; used by discovery. */
    suspend fun describe(packageName: String): NodeDescriptor {
        val lease = pool.acquire(packageName)
        try {
            val bytes = try {
                withContext(binderContext) { lease.node.describe() }
            } catch (e: RemoteException) {
                throw unavailable("'$packageName' is not reachable", e)
            } catch (e: SecurityException) {
                throw unavailable("'$packageName' does not trust this app", e)
            }
            return decode(NodeDescriptor.serializer(), bytes, "descriptor")
        } finally {
            lease.release()
        }
    }

    private fun <T> encodeChecked(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): ByteArray {
        val bytes = codec.encode(serializer, value)
        if (bytes.size > maxEnvelopeBytes) {
            throw KoraException(KoraError(ErrorCode.PAYLOAD_TOO_LARGE, "Envelope of ${bytes.size} bytes exceeds $maxEnvelopeBytes"))
        }
        return bytes
    }

    private fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, bytes: ByteArray, what: String): T =
        try {
            codec.decode(deserializer, bytes)
        } catch (e: IllegalArgumentException) {
            throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed $what from peer"), e)
        }

    private fun unavailable(message: String, cause: Throwable? = null) =
        KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, message), cause)

    companion object {
        const val DEFAULT_EVENT_BUFFER: Int = 256
    }
}
