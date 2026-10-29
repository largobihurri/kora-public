package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The serving side: executes what the counterpart asks against the local [node], on behalf of [counterpart].
 *
 * **Identity.** [counterpart] is fixed by whoever builds the server (on Android `Caller(PEER, "wear:<package>")`);
 * it is never read from a frame. The counterpart is the same app on the other device, authenticated by the Data
 * Layer, so it is served as a trusted `PEER`. Requests for the node itself go through [KoraNode.dispatch] and
 * [KoraNode.dispatchSubscribe], so they pass the node's own caller policy and audit like any other access, and the
 * peers of this device see this node (not the counterpart) as the caller.
 *
 * **Targets.** A frame names a target: this node's id, or the id of one of its **local** peers. Anything else,
 * in particular ids with [WearProtocol.PEER_PREFIX] (peers of the counterpart itself), is `provider_unavailable`:
 * a request can never be bounced back to the device it came from.
 *
 * **Peer list.** `describe` is answered with this node and its local peers. After the first `describe` the server
 * also pushes `peers_changed` whenever the list changes, until the link is lost or the counterpart restarts.
 *
 * **Limits.** At most [maxSubscriptions] live subscriptions; frames over [WearProtocol.MAX_FRAME_BYTES] are
 * answered with `payload_too_large`. When the link is lost, or the counterpart app restarts, every subscription
 * is cancelled, so no source (and no peer behind it) keeps producing for a counterpart that is gone.
 */
public class RelayServer(
    private val node: KoraNode,
    private val link: FramedLink,
    private val scope: CoroutineScope,
    private val counterpart: Caller,
    private val maxSubscriptions: Int = DEFAULT_MAX_SUBSCRIPTIONS,
) {
    private val subscriptions = ConcurrentHashMap<String, Job>()

    @Volatile
    private var watched = false

    /** Number of subscriptions being served. */
    public val activeSubscriptions: Int get() = subscriptions.size

    init {
        require(maxSubscriptions > 0) { "maxSubscriptions must be positive" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.Received -> receive(event.body)
                    LinkEvent.Lost, LinkEvent.CounterpartRestarted -> reset()
                    LinkEvent.Reconnected -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            node.peers.map { snapshot() }.distinctUntilChanged().drop(1).collect { nodes ->
                if (watched) runCatching { link.send(FrameBody.PeersChanged(nodes)) }
            }
        }
    }

    private fun receive(body: FrameBody) {
        when (body) {
            is FrameBody.DescribePeers -> scope.launch {
                watched = true
                runCatching { link.send(FrameBody.Peers(body.id, snapshot())) }
            }
            is FrameBody.Call -> scope.launch { answer(body) }
            is FrameBody.Subscribe -> subscribe(body)
            is FrameBody.Cancel -> subscriptions.remove(body.subscriptionId)?.cancel()
            else -> Unit // answers, events and pushes are for the client side
        }
    }

    private fun reset() {
        watched = false
        for ((id, job) in subscriptions) {
            if (subscriptions.remove(id, job)) job.cancel()
        }
    }

    /** This node first, then its local peers (never the ones relayed from the counterpart). */
    private fun snapshot(): List<NodeDescriptor> =
        listOf(node.describe()) + node.peers.value.filterNot { it.nodeId.startsWith(WearProtocol.PEER_PREFIX) }.map { it.descriptor }

    /** The routing target for [id], or null when the counterpart may not address it. */
    private fun targetOf(id: String): Target? = when {
        id == node.nodeId -> Target.Local
        id.startsWith(WearProtocol.PEER_PREFIX) -> null
        node.peers.value.any { it.nodeId == id } -> Target.Peer(id)
        else -> null
    }

    private suspend fun answer(call: FrameBody.Call) {
        val request = call.request
        val target = targetOf(call.target)
        val response: KoraResponse = if (target == null) {
            failure(request.id, ErrorCode.PROVIDER_UNAVAILABLE, "'${call.target}' is not reachable from this device")
        } else {
            try {
                node.dispatch(counterpart, request, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(request.id, ErrorCode.INTERNAL, "Internal error")
            }
        }
        try {
            link.send(FrameBody.CallResult(response))
        } catch (e: KoraException) {
            if (e.error.code == ErrorCode.PAYLOAD_TOO_LARGE) {
                runCatching {
                    link.send(FrameBody.CallResult(failure(request.id, ErrorCode.PAYLOAD_TOO_LARGE, "Response exceeds ${WearProtocol.MAX_FRAME_BYTES} bytes")))
                }
            } // otherwise the counterpart is unreachable: nobody is waiting for the answer
        }
    }

    private fun subscribe(frame: FrameBody.Subscribe) {
        val request = frame.request
        val id = request.subscriptionId
        val refusal = when {
            id.isEmpty() || id.length > MAX_ID_LENGTH -> KoraError(ErrorCode.INVALID_PAYLOAD, "Invalid subscription id")
            subscriptions.size >= maxSubscriptions -> KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Too many subscriptions")
            subscriptions.containsKey(id) -> KoraError(ErrorCode.INVALID_PAYLOAD, "Subscription id '$id' is already open")
            else -> null
        }
        val target = targetOf(frame.target)
        if (refusal != null || target == null) {
            val error = refusal ?: KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "'${frame.target}' is not reachable from this device")
            scope.launch { runCatching { link.send(FrameBody.Failed(id, error)) } }
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { pump(request, target) }
        subscriptions[id] = job
        job.invokeOnCompletion { subscriptions.remove(id, job) }
        job.start()
    }

    private suspend fun pump(request: SubscribeRequest, target: Target) {
        val id = request.subscriptionId
        try {
            node.dispatchSubscribe(counterpart, request, target).collect { event ->
                try {
                    link.send(FrameBody.Event(event))
                } catch (e: KoraException) {
                    // Too large to carry: report it. Anything else means the counterpart is gone: stop quietly.
                    if (e.error.code == ErrorCode.PAYLOAD_TOO_LARGE) throw e
                    throw CancellationException("The counterpart is unreachable", e)
                }
            }
            runCatching { link.send(FrameBody.Completed(id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoraException) {
            runCatching { link.send(FrameBody.Failed(id, e.error)) }
        } catch (e: Exception) {
            runCatching { link.send(FrameBody.Failed(id, KoraError(ErrorCode.INTERNAL, "Internal error"))) }
        }
    }

    private fun failure(id: String, code: ErrorCode, message: String) = KoraResponse.Failure(id, KoraError(code, message))

    public companion object {
        public const val DEFAULT_MAX_SUBSCRIPTIONS: Int = 32
        private const val MAX_ID_LENGTH = 128
    }
}
