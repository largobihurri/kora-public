package eus.bihurri.kora.core

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.SubscribeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * One way of reaching peers: the [directory] that lists them and the [transport] that talks to them. A node has a
 * single directory and a single transport, so a node that reaches peers in several ways (Binder on the device and
 * the Wear Data Layer to the other device, for example) combines its routes with [CompositePeerDirectory] and
 * [RoutingPeerTransport].
 */
public class PeerRoute(public val directory: PeerDirectory, public val transport: PeerTransport)

/**
 * Merges several directories into one list, ordered by node id. When two directories list the same node id the
 * **first** one wins, the same rule [RoutingPeerTransport] uses to pick the transport, so a peer is always called
 * through the route that listed it.
 *
 * The merged list is kept up to date by a collector on [scope] (eagerly, so `value` is always readable); it follows
 * each source with the latency of that scope's dispatcher.
 */
public class CompositePeerDirectory(directories: List<PeerDirectory>, scope: CoroutineScope) : PeerDirectory {
    override val peers: StateFlow<List<PeerInfo>> = if (directories.isEmpty()) {
        PeerDirectory.None.peers
    } else {
        combine(directories.map { it.peers }) { lists -> merge(lists.asList()) }
            .stateIn(scope, SharingStarted.Eagerly, merge(directories.map { it.peers.value }))
    }

    private fun merge(lists: List<List<PeerInfo>>): List<PeerInfo> =
        lists.flatten().distinctBy { it.nodeId }.sortedBy { it.nodeId }
}

/**
 * Sends each call through the route whose directory currently lists the peer (the first one, when several do).
 * A peer no directory lists is `provider_unavailable`.
 *
 * Streams follow the same choice: a stream call goes through the chosen route only when that route's transport is a
 * [StreamingPeerTransport], which then decides for its peer. Otherwise the call is `provider_unavailable` before
 * anything is sent; it never falls back to another route.
 */
public class RoutingPeerTransport(private val routes: List<PeerRoute>) : StreamingPeerTransport {
    override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse = route(peer).call(peer, request)

    override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = flow {
        emitAll(route(peer).subscribe(peer, request))
    }

    override suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse =
        streamingRoute(peer).callSending(peer, request, bytes)

    override suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse =
        streamingRoute(peer).callReceiving(peer, request)

    private fun route(peer: PeerInfo): PeerTransport =
        routes.firstOrNull { route -> route.directory.peers.value.any { it.nodeId == peer.nodeId } }?.transport
            ?: throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer '${peer.nodeId}' is not reachable"))

    private fun streamingRoute(peer: PeerInfo): StreamingPeerTransport = route(peer) as? StreamingPeerTransport
        ?: throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The route to '${peer.nodeId}' carries no byte streams"))
}
