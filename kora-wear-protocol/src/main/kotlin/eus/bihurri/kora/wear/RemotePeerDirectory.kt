package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.core.PeerDirectory
import eus.bihurri.kora.core.PeerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The peers of the counterpart device, as a [PeerDirectory]: the counterpart app itself and the peers it can reach
 * (for the Kardio watch: the Kardio phone app, Sonora on the phone, ...). Their node ids are prefixed with
 * [WearProtocol.PEER_PREFIX] (`wear:eus.bihurri.sonora`) so they never collide with the ids of local peers or with
 * this node's own id, which the counterpart app shares.
 *
 * The list is **empty until [refresh]** is called: nothing is sent just because the app started, because a message
 * can wake the other device's app. Ask when the user is about to need the other device (opening the music page,
 * starting a recording). Once asked, the directory follows the counterpart by itself: it applies the
 * `peers_changed` pushes, empties on a link loss, and asks again when the link comes back or the counterpart app
 * restarted. Before the first [refresh] none of that costs anything.
 *
 * @param describeTimeout how long [refresh] waits for the answer.
 * @param maxPeers peers kept from one answer; the rest is ignored (a counterpart cannot flood this node).
 */
public class RemotePeerDirectory(
    private val link: FramedLink,
    private val scope: CoroutineScope,
    private val describeTimeout: Duration = 10.seconds,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) : PeerDirectory {
    private val state = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peers: StateFlow<List<PeerInfo>> = state

    private val answers = ConcurrentHashMap<String, CompletableDeferred<List<NodeDescriptor>>>()
    private val sequence = AtomicLong()
    private val refreshing = AtomicBoolean()

    @Volatile
    private var interested = false

    init {
        require(describeTimeout.isPositive()) { "describeTimeout must be positive: $describeTimeout" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.Received -> receive(event.body)
                    LinkEvent.Lost -> {
                        failAnswers()
                        state.value = emptyList()
                    }
                    LinkEvent.CounterpartRestarted -> {
                        failAnswers()
                        state.value = emptyList()
                        refreshInBackground()
                    }
                    LinkEvent.Reconnected -> refreshInBackground()
                }
            }
        }
    }

    /**
     * Asks the counterpart for its peers and applies the answer. Returns whether it answered; when it did not
     * (unreachable, timeout) the list is emptied, because calls to peers nobody can reach would only time out.
     * Marks this directory as interested, so later changes are followed automatically.
     */
    public suspend fun refresh(): Boolean {
        interested = true
        val id = "peers-${sequence.incrementAndGet()}"
        val answer = CompletableDeferred<List<NodeDescriptor>>()
        answers[id] = answer
        return try {
            link.send(FrameBody.DescribePeers(id))
            val nodes = withTimeout(describeTimeout) { answer.await() }
            apply(nodes)
            true
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                state.value = emptyList()
                false
            } else {
                throw e
            }
        } catch (_: KoraException) {
            state.value = emptyList()
            false
        } finally {
            answers.remove(id, answer)
        }
    }

    /** Like [refresh], without waiting; for callers that cannot suspend (a click handler). Ignored while one runs. */
    public fun requestRefresh() {
        interested = true
        refreshInBackground()
    }

    private fun refreshInBackground() {
        if (!interested || !refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                refresh()
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun receive(body: FrameBody) {
        when (body) {
            is FrameBody.Peers -> answers[body.id]?.complete(body.nodes)
            is FrameBody.PeersChanged -> if (interested) apply(body.nodes)
            else -> Unit
        }
    }

    private fun apply(nodes: List<NodeDescriptor>) {
        state.value = nodes
            .filter { it.nodeId.isNotBlank() && !it.nodeId.startsWith(WearProtocol.PEER_PREFIX) }
            .distinctBy { it.nodeId }
            .take(maxPeers)
            .map { PeerInfo(it.copy(nodeId = WearProtocol.PEER_PREFIX + it.nodeId)) }
            .sortedBy { it.nodeId }
    }

    private fun failAnswers() {
        for ((id, answer) in answers) {
            if (answers.remove(id, answer)) answer.completeExceptionally(unavailable("The counterpart device is no longer reachable"))
        }
    }

    public companion object {
        public const val DEFAULT_MAX_PEERS: Int = 32
    }
}
