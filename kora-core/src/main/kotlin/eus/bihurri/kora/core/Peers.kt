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
import java.io.Closeable

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

/**
 * Port: a [PeerTransport] that can also carry a [ByteStream] beside the envelopes, for operations whose
 * `OperationSpec.stream` is not `NONE`. [PeerTransport] itself stays as it is; a node sends a stream operation to a
 * peer only through a transport of this type, and fails with `provider_unavailable` otherwise.
 *
 * Whether a peer can take a stream is known only to the transport (a peer on an older protocol cannot), so both
 * calls throw [KoraException] with `provider_unavailable` **before writing anything** when the peer they are given
 * cannot take one. The rules of [PeerTransport] apply otherwise: transport failures are [KoraException], and the
 * transport establishes the caller the peer sees.
 *
 * A stream call is not bound by the node's call timeout; a transport bounds the transfer itself, typically with an
 * idle timeout. When its own timeout fires, it throws [KoraException] with `timeout`, never a
 * `TimeoutCancellationException` or any other `CancellationException`: the node passes cancellation through
 * untranslated in stream calls, so its caller would take it for the cancellation of its own coroutine.
 */
public interface StreamingPeerTransport : PeerTransport {
    /**
     * Delivers [request] with [bytes] and returns the peer's answer. The transport reads [bytes] only until it
     * returns or throws; the node closes them afterwards in any case.
     */
    public suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse

    /**
     * Delivers [request] and returns the peer's answer with the bytes it offers. The caller of this method owns
     * the returned bytes and closes them.
     */
    public suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse
}

/**
 * The answer to a download ([KoraNode.handleReceiving], [StreamingPeerTransport.callReceiving]): the [response]
 * envelope and, exactly when it is a [KoraResponse.Success], the [bytes] that come with it. Whoever holds it owns the
 * bytes; closing it closes them.
 */
public class StreamedResponse(public val response: KoraResponse, public val bytes: ByteStream?) : Closeable {
    init {
        require((bytes != null) == (response is KoraResponse.Success)) {
            "A success carries bytes and a failure carries none"
        }
    }

    override fun close() {
        bytes?.close()
    }
}
