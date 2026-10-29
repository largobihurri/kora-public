package eus.bihurri.kora.wear

import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.PeerRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything one device needs to talk to its counterpart over a [WireLink], wired in the right order:
 *
 * ```
 * val relay = WearRelay(link, scope)
 * val node = KoraNode(..., peerDirectory = CompositePeerDirectory(listOf(binder, relay.route.directory), scope),
 *                     transport = RoutingPeerTransport(listOf(binderRoute, relay.route)))
 * relay.serve(node, Caller(CallerKind.PEER, "wear:<counterpart>"))   // starts reading the link
 * relay.refresh()                                                     // when the other device's peers are needed
 * ```
 *
 * The same class runs on the phone and on the watch: each side lists the other's peers ([route]) and serves the
 * other's requests ([serve]).
 *
 * @param callTimeout limit for one call to the counterpart; see [RemotePeerTransport].
 */
public class WearRelay(
    link: WireLink,
    private val scope: CoroutineScope,
    callTimeout: Duration = 10.seconds,
    describeTimeout: Duration = 10.seconds,
    onDropped: (String) -> Unit = {},
) {
    private val framed = FramedLink(link, scope, onDropped = onDropped)

    /** The counterpart's peers as a directory, to combine with the others of the node. */
    public val directory: RemotePeerDirectory = RemotePeerDirectory(framed, scope, describeTimeout)

    /** How to reach them. */
    public val transport: RemotePeerTransport = RemotePeerTransport(framed, scope, callTimeout)

    /** [directory] and [transport] as one route. */
    public val route: PeerRoute = PeerRoute(directory, transport)

    /** The counterpart's peers, prefixed with [WearProtocol.PEER_PREFIX]; empty until [refresh]. */
    public val remotePeers: StateFlow<List<eus.bihurri.kora.core.PeerInfo>> get() = directory.peers

    private var server: RelayServer? = null

    /**
     * Starts serving [node] to the counterpart as [counterpart] and starts reading the link. Call it once, after the
     * node exists; frames that arrive earlier are lost, so the link must not deliver before this.
     */
    @Synchronized
    public fun serve(node: KoraNode, counterpart: Caller): RelayServer {
        check(server == null) { "Already serving" }
        return RelayServer(node, framed, scope, counterpart).also {
            server = it
            framed.start()
        }
    }

    /** Asks the counterpart for its peers now (see [RemotePeerDirectory.refresh]). */
    public suspend fun refresh(): Boolean = directory.refresh()

    /** Like [refresh] without waiting. */
    public fun requestRefresh(): Unit = directory.requestRefresh()
}
