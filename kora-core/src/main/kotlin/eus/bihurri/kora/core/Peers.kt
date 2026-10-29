package eus.bihurri.kora.core

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Another node reachable through the [PeerTransport], as advertised by its [descriptor]. */
public data class PeerInfo(public val descriptor: NodeDescriptor) {
    public val nodeId: String get() = descriptor.nodeId
}

/** Where a typed call or subscription should go. */
public sealed interface Target {
    /** This node if it provides a compatible capability, otherwise the first peer that does. */
    public data object Any : Target

    /** Only this node. */
    public data object Local : Target

    /** Only the peer with this node id. */
    public data class Peer(public val nodeId: String) : Target
}

/** Port: the peers currently reachable. A node without peers (a standalone app) uses [None]. */
public interface PeerDirectory {
    public val peers: StateFlow<List<PeerInfo>>

    public object None : PeerDirectory {
        override val peers: StateFlow<List<PeerInfo>> = MutableStateFlow(emptyList())
    }
}

/**
 * Port: delivers requests to a peer and returns its answer.
 *
 * Implementations own the wire concerns (encoding the envelopes, binding, reconnecting) and must report
 * transport failures as [KoraException] (for example `provider_unavailable`). The peer sees the request as
 * coming from [CallerKind.PEER] with the calling node id, established by the transport and not by the request.
 */
public interface PeerTransport {
    public suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse

    /**
     * Opens the event stream. Refusals and transport failures surface as [KoraException] thrown when the flow is
     * collected; cancelling the collector ends the subscription.
     */
    public fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent>

    /** Transport of a standalone node: every peer is unavailable. */
    public object None : PeerTransport {
        private fun unavailable() =
            KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "No peer transport configured"))

        override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse = throw unavailable()

        override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = flow { throw unavailable() }
    }
}
