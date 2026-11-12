package eus.bihurri.kora.android

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import eus.bihurri.kora.android.ipc.IKoraCallback
import eus.bihurri.kora.android.ipc.IKoraEventSink
import eus.bihurri.kora.android.ipc.IKoraStreamCallback
import eus.bihurri.kora.android.ipc.IKoraSubscription
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.StreamedResponse
import eus.bihurri.kora.core.StreamingPeerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [StreamingPeerTransport] over Binder. A peer is addressed by its package name, which is its node id (see
 * [AndroidPeerDirectory]); connections come from the shared [pool] and are bound lazily.
 *
 * Failures reach the node as [KoraException]: `provider_unavailable` when the peer cannot be reached or dies,
 * `payload_too_large` for envelopes over [maxEnvelopeBytes], and whatever error the peer answered with.
 * Cancelling a call is safe: a late answer is dropped. Cancelling a subscription tells the peer to stop.
 *
 * Byte streams (protocol version 2, `IKoraNode.callSending` and `callReceiving`) go only to a peer whose connection
 * reported [KoraAndroid.STREAMS_PROTOCOL_VERSION] or more; with an older one a stream call is `provider_unavailable`
 * before any transaction. The bytes cross through a pipe whose read end travels in the transaction; this side pumps
 * an upload into the write end on [pumpContext], and reads a download from the read end it receives. A stream call
 * is not bound by the node's call timeout: it fails with `timeout` when, for [streamIdleTimeout], no byte moves while
 * one is awaited and no answer arrives (see [IdleWatchdog]). Every descriptor this side holds is closed on every path.
 *
 * @param binderContext where Binder calls that can block are made; the default keeps them off the caller thread.
 * @param streamScope runs the watchdogs and pumps of streams, which can outlive the call that started them (a
 *   download is read after its call returns); when it ends, the streams in flight fail.
 * @param descriptors creates pipes and opens descriptors; replaced in tests.
 * @param pumpContext where uploads are pumped: blocking, so neither a Binder thread nor the caller's dispatcher.
 * @param timeSource the clock of the watchdogs; tests pass their virtual one.
 */
internal class BinderPeerTransport(
    private val pool: ConnectionPool,
    private val codec: KoraCodec = CborCodec,
    private val maxEnvelopeBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
    private val eventBuffer: Int = DEFAULT_EVENT_BUFFER,
    private val binderContext: CoroutineContext = Dispatchers.IO,
    private val streamScope: CoroutineScope,
    internal val streamIdleTimeout: Duration = DEFAULT_STREAM_IDLE_TIMEOUT,
    private val descriptors: Descriptors = AndroidDescriptors,
    private val pumpContext: CoroutineContext = Dispatchers.IO,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : StreamingPeerTransport {

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

    /**
     * Sends [request] with [bytes] to [peer] and returns its answer. The local copy of the pipe's read end is closed
     * once the transaction is sent; the write end is closed when the pump is done, or when this returns or throws
     * first (the answer came early, the peer died, the stream stalled, the caller was cancelled). [bytes] may still be
     * read by a pump that is blocked on them when this returns; the node closes them right after, which stops it.
     *
     * When [bytes] themselves fail (they end short, run over or break), the call fails at once with
     * `invalid_payload` (or with their own [KoraException]), and the peer sees a stream it rejects.
     *
     * The watchdog keeps counting after the last byte is written: the answer must arrive within [streamIdleTimeout]
     * of it, or the call fails with `timeout`. So the peer's handler starts reading promptly (the pump fills the pipe
     * and then waits for room) and answers soon after the last byte: for `files.upload` it only stages the file, and
     * slow work waits for the `add` that uses it. A caller that retries after a `timeout` stages a new file; the one
     * it abandoned expires unused.
     */
    override suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse {
        val envelope = encodeChecked(KoraRequest.serializer(), request)
        val lease = pool.acquire(peer.nodeId)
        try {
            requireStreams(peer, lease)
            val pipe = openPipe(peer)
            val sink = descriptors.output(pipe.writeEnd)
            val answer = CompletableDeferred<ByteArray>()
            val watchdog = IdleWatchdog(streamIdleTimeout, timeSource).apply { startWaiting() }
            val timer = streamScope.launch {
                watchdog.awaitIdle()
                answer.completeExceptionally(stalled(peer))
            }
            timer.invokeOnCompletion { answer.completeExceptionally(unavailable("The transport is closed")) }
            val watch = lease.onLost { answer.completeExceptionally(unavailable("'${peer.nodeId}' disconnected")) }
            try {
                val callback = object : IKoraCallback.Stub() {
                    override fun onResponse(response: ByteArray) {
                        answer.complete(response)
                    }
                }
                try {
                    withContext(binderContext) { lease.node.callSending(envelope, pipe.readEnd, bytes.sizeBytes, callback) }
                } catch (e: RemoteException) {
                    throw unavailable("'${peer.nodeId}' is not reachable", e)
                } finally {
                    pipe.readEnd.closeQuietly() // the peer holds its own copy now
                }
                streamScope.launch(pumpContext) {
                    val result = pump(bytes.input, bytes.sizeBytes, sink, watchdog)
                    if (result is PumpResult.SourceFailed) answer.completeExceptionally(sourceFailure(result.error))
                }.invokeOnCompletion { sink.closeQuietly() } // also when the pump never ran
                return decode(KoraResponse.serializer(), answer.await(), "response")
            } finally {
                watch.close()
                timer.cancel()
                pipe.readEnd.closeQuietly()
                sink.closeQuietly()
            }
        } finally {
            lease.release()
        }
    }

    /**
     * Sends [request] to [peer] and returns its answer with the bytes it offers. The bytes are read from the
     * descriptor the peer sent; they hold the connection until they are closed or read to their end, and fail with
     * `provider_unavailable` when the peer dies and with `timeout` when a read waits [streamIdleTimeout] for a byte.
     * A failure answer carries no bytes; a descriptor that comes with it anyway, or with an answer nobody waits for
     * any more, is closed.
     */
    override suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse {
        val envelope = encodeChecked(KoraRequest.serializer(), request)
        val lease = pool.acquire(peer.nodeId)
        var handedOver = false
        try {
            requireStreams(peer, lease)
            val answer = CompletableDeferred<StreamAnswer>()
            val watchdog = IdleWatchdog(streamIdleTimeout, timeSource).apply { startWaiting() }
            val timer = streamScope.launch {
                watchdog.awaitIdle()
                answer.completeExceptionally(stalled(peer))
            }
            timer.invokeOnCompletion { answer.completeExceptionally(unavailable("The transport is closed")) }
            val watch = lease.onLost { answer.completeExceptionally(unavailable("'${peer.nodeId}' disconnected")) }
            val received = try {
                val callback = object : IKoraStreamCallback.Stub() {
                    override fun onResponse(response: ByteArray, bytes: ParcelFileDescriptor?, sizeBytes: Long) {
                        // Nobody takes a descriptor that comes after the call failed, timed out or was cancelled.
                        if (!answer.complete(StreamAnswer(response, bytes, sizeBytes))) bytes?.closeQuietly()
                    }
                }
                try {
                    withContext(binderContext) { lease.node.callReceiving(envelope, callback) }
                } catch (e: RemoteException) {
                    throw unavailable("'${peer.nodeId}' is not reachable", e)
                }
                answer.await()
            } catch (e: Throwable) {
                // The answer may have arrived anyway: whoever completes the deferred first owns the descriptor.
                if (!answer.completeExceptionally(e)) answer.receivedOrNull()?.bytes?.closeQuietly()
                throw e
            } finally {
                watch.close()
                timer.cancel()
            }
            return streamed(peer, lease, received).also { handedOver = it.bytes != null }
        } finally {
            if (!handedOver) lease.release()
        }
    }

    /** The answer to a download as it arrived: the CBOR response and, for a success, the descriptor of its bytes. */
    private class StreamAnswer(val response: ByteArray, val bytes: ParcelFileDescriptor?, val sizeBytes: Long)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun CompletableDeferred<StreamAnswer>.receivedOrNull(): StreamAnswer? =
        if (isCompleted && getCompletionExceptionOrNull() == null) getCompleted() else null

    /**
     * Turns what [peer] answered into a [StreamedResponse]. A success hands over [lease], released when its bytes are
     * done; any other outcome leaves it to the caller, and closes the descriptor.
     */
    private fun streamed(peer: PeerInfo, lease: ConnectionPool.Lease, received: StreamAnswer): StreamedResponse {
        val descriptor = received.bytes
        val response = try {
            decode(KoraResponse.serializer(), received.response, "response").also {
                if (it is KoraResponse.Success && (descriptor == null || received.sizeBytes < 0)) {
                    throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Malformed download answer from peer: no byte stream"))
                }
            }
        } catch (e: Throwable) {
            descriptor?.closeQuietly()
            throw e
        }
        if (response is KoraResponse.Failure || descriptor == null) {
            descriptor?.closeQuietly()
            return StreamedResponse(response, null)
        }
        val lost = AtomicReference<AutoCloseable?>()
        val input = WatchedInput(descriptors.input(descriptor), streamIdleTimeout, streamScope, timeSource) {
            lost.getAndSet(Finished)?.close()
            lease.release()
        }
        val watch = lease.onLost { input.fail(unavailable("'${peer.nodeId}' disconnected")) }
        if (!lost.compareAndSet(null, watch)) watch.close() // the bytes were already done
        return StreamedResponse(response, ByteStream(received.sizeBytes, input))
    }

    /** Refuses a stream call to a peer whose protocol cannot carry one, before anything is sent. */
    private fun requireStreams(peer: PeerInfo, lease: ConnectionPool.Lease) {
        if (lease.protocolVersion < KoraAndroid.STREAMS_PROTOCOL_VERSION) {
            throw unavailable("'${peer.nodeId}' speaks protocol ${lease.protocolVersion}, which carries no byte streams")
        }
    }

    private fun openPipe(peer: PeerInfo): Pipe = try {
        descriptors.createPipe()
    } catch (e: IOException) {
        throw unavailable("Cannot open a pipe to '${peer.nodeId}'", e)
    }

    private fun stalled(peer: PeerInfo) =
        KoraException(KoraError(ErrorCode.TIMEOUT, "The stream with '${peer.nodeId}' stalled for $streamIdleTimeout"))

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

        /** Stands for a lost-peer watch that is no longer needed. */
        private val Finished = AutoCloseable {}
    }
}
