package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.PeerTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [PeerTransport] toward the peers of the counterpart device, carried by a [FramedLink]. Peers are those listed by
 * the [RemotePeerDirectory] of the same link, whose node ids start with [WearProtocol.PEER_PREFIX]; the prefix is
 * removed before the request crosses, because the other device knows the peer by its plain id.
 *
 * - A call is one frame out and one frame back; it fails with `timeout` after [callTimeout], and with
 *   `provider_unavailable` when the link cannot send or is lost while waiting. Cancelling the caller drops a late
 *   answer; the other device is not told (calls have no cancel frame).
 * - A subscription is one frame out, then one frame per event. Cancelling the collector sends a `cancel` frame
 *   (best effort). A link loss, or a restart of the counterpart app, ends every stream with `provider_unavailable`.
 *   There is no keep-alive, so a counterpart that dies silently cannot be noticed until its next frame.
 *
 * Failures reach the node as [KoraException], like with the Binder transport.
 *
 * @param callTimeout how long to wait for the answer to one call. Keep it above the node's own call timeout if
 *   you want the node's `timeout` to win; a call crosses two radios and may wake a cold app on the other side.
 * @param eventBuffer events held for a slow collector before the stream fails with `internal`.
 */
public class RemotePeerTransport(
    private val link: FramedLink,
    private val scope: CoroutineScope,
    private val callTimeout: Duration = 10.seconds,
    private val eventBuffer: Int = DEFAULT_EVENT_BUFFER,
) : PeerTransport {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<KoraResponse>>()
    private val streams = ConcurrentHashMap<String, StreamSink>()

    private interface StreamSink {
        /** Called for every event; returns false when the stream cannot take it. */
        fun onEvent(event: KoraEvent): Boolean

        fun onFailed(error: KoraError)

        fun onCompleted()

        fun onLost(message: String)

        fun tooSlow()
    }

    init {
        require(callTimeout.isPositive()) { "callTimeout must be positive: $callTimeout" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.Received -> receive(event.body)
                    LinkEvent.Lost -> failEverything("The counterpart device is no longer reachable")
                    LinkEvent.CounterpartRestarted -> failEverything("The counterpart app restarted")
                    LinkEvent.Reconnected -> Unit
                }
            }
        }
    }

    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
        val target = targetOf(peer)
        val answer = CompletableDeferred<KoraResponse>()
        check(pending.putIfAbsent(request.id, answer) == null) { "Request id '${request.id}' is already in flight" }
        try {
            link.send(FrameBody.Call(target, request))
            return try {
                withTimeout(callTimeout) { answer.await() }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive() // a timeout of an enclosing scope is not ours to translate
                throw KoraException(KoraError(ErrorCode.TIMEOUT, "'${peer.nodeId}' did not answer within $callTimeout"), e)
            }
        } finally {
            pending.remove(request.id, answer)
        }
    }

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = callbackFlow {
        val target = targetOf(peer)
        val id = request.subscriptionId
        val sink = ChannelSink(this)
        if (streams.putIfAbsent(id, sink) != null) {
            throw KoraException(KoraError(ErrorCode.INTERNAL, "Subscription id '$id' is already open"))
        }
        try {
            link.send(FrameBody.Subscribe(target, request))
        } catch (e: Throwable) {
            streams.remove(id, sink)
            throw e
        }
        awaitClose {
            streams.remove(id, sink)
            if (!sink.ended) {
                // Tell the other device to stop, so its source (and any peer behind it) is released.
                scope.launch { runCatching { link.send(FrameBody.Cancel(id)) } }
            }
        }
    }.buffer(eventBuffer)

    private inner class ChannelSink(private val channel: ProducerScope<KoraEvent>) : StreamSink {
        @Volatile
        var ended = false

        override fun onEvent(event: KoraEvent): Boolean = channel.trySend(event).isSuccess

        override fun onFailed(error: KoraError) {
            ended = true
            channel.close(KoraException(error))
        }

        override fun onCompleted() {
            ended = true
            channel.close()
        }

        override fun onLost(message: String) {
            ended = true
            channel.close(unavailable(message))
        }

        override fun tooSlow() {
            ended = true
            channel.close(KoraException(KoraError(ErrorCode.INTERNAL, "Consumer is too slow: event buffer is full")))
        }
    }

    private fun targetOf(peer: PeerInfo): String {
        val id = peer.nodeId
        if (!id.startsWith(WearProtocol.PEER_PREFIX) || id.length == WearProtocol.PEER_PREFIX.length) {
            throw unavailable("'$id' is not a peer of the counterpart device")
        }
        return id.removePrefix(WearProtocol.PEER_PREFIX)
    }

    private fun receive(body: FrameBody) {
        when (body) {
            is FrameBody.CallResult -> pending[body.response.id]?.complete(body.response)
            is FrameBody.Event -> streams[body.event.subscriptionId]?.let { sink ->
                if (!sink.onEvent(body.event)) {
                    sink.tooSlow()
                    // Our collector is overloaded: stop the stream on the other side as well.
                    scope.launch { runCatching { link.send(FrameBody.Cancel(body.event.subscriptionId)) } }
                    streams.remove(body.event.subscriptionId, sink)
                }
            }
            is FrameBody.Failed -> streams.remove(body.subscriptionId)?.onFailed(body.error)
            is FrameBody.Completed -> streams.remove(body.subscriptionId)?.onCompleted()
            else -> Unit // requests and peer lists are not for the transport
        }
    }

    private fun failEverything(message: String) {
        for ((id, answer) in pending) {
            if (pending.remove(id, answer)) answer.completeExceptionally(unavailable(message))
        }
        for ((id, sink) in streams) {
            if (streams.remove(id, sink)) sink.onLost(message)
        }
    }

    public companion object {
        public const val DEFAULT_EVENT_BUFFER: Int = 256
    }
}
