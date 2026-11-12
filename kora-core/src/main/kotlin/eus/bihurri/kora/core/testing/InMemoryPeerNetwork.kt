package eus.bihurri.kora.core.testing

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.core.ByteStream
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.PeerDirectory
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.StreamedResponse
import eus.bihurri.kora.core.StreamingPeerTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer

/**
 * Connects several [KoraNode]s inside one process, for tests of the library and of apps built on it.
 *
 * Every envelope crosses the network through [codec] (encoded to bytes and decoded again), so the wire path is
 * exercised without Android. The callee sees the caller as `Caller(PEER, callerNodeId)`. Endpoints are
 * [StreamingPeerTransport]s: the byte stream of a stream call passes through as the same object, beside envelopes
 * that cross like any other.
 *
 * ```
 * val network = InMemoryPeerNetwork()
 * val a = network.endpoint("a"); val b = network.endpoint("b")
 * val nodeA = KoraNode("a", peerDirectory = a, transport = a)
 * val nodeB = KoraNode("b", peerDirectory = b, transport = b)
 * nodeA.provide(...) { ... }
 * a.connect(nodeA); b.connect(nodeB)   // after registering providers: peers see what is registered now
 * ```
 */
public class InMemoryPeerNetwork(private val codec: KoraCodec = CborCodec) {
    @Volatile
    private var streams = true

    /**
     * A network whose endpoints carry byte streams only when [streams] is true. With `false` every stream call fails
     * with `provider_unavailable` before anything is delivered, as through a transport whose peers cannot take a
     * stream, while plain calls and subscriptions work as usual.
     */
    public constructor(codec: KoraCodec = CborCodec, streams: Boolean) : this(codec) {
        this.streams = streams
    }

    private val lock = Any()
    private val endpoints = mutableMapOf<String, Endpoint>()

    /** The endpoint (directory and transport) for the node with [nodeId]; the same instance on repeat calls. */
    public fun endpoint(nodeId: String): Endpoint = synchronized(lock) { endpoints.getOrPut(nodeId) { Endpoint(nodeId) } }

    /**
     * Recomputes what every endpoint sees. Call it after a connected node registers more capabilities, because
     * descriptors are snapshots taken when a node connects.
     */
    public fun refresh() {
        synchronized(lock) {
            val connected = endpoints.values.filter { it.node != null }
            for (endpoint in endpoints.values) {
                endpoint.peerList.value = connected
                    .filter { it !== endpoint }
                    .map { PeerInfo(it.node!!.describe()) }
                    .sortedBy { it.nodeId }
            }
        }
    }

    /** One node's view of the network: implements both ports. */
    public inner class Endpoint internal constructor(public val nodeId: String) : PeerDirectory, StreamingPeerTransport {
        internal var node: KoraNode? = null
            private set
        internal val peerList = MutableStateFlow<List<PeerInfo>>(emptyList())

        override val peers: StateFlow<List<PeerInfo>> = peerList

        /** Puts [node] on the network (it becomes visible to the other endpoints). */
        public fun connect(node: KoraNode) {
            require(node.nodeId == nodeId) { "Endpoint '$nodeId' cannot host node '${node.nodeId}'" }
            synchronized(lock) { this.node = node }
            refresh()
        }

        /** Takes the node off the network: peers stop seeing it and calls to it fail as unavailable. */
        public fun disconnect() {
            synchronized(lock) { node = null }
            refresh()
        }

        override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
            val callee = target(peer)
            val delivered = codec.decode(KoraRequest.serializer(), codec.encode(KoraRequest.serializer(), request))
            val answer = callee.handle(Caller(CallerKind.PEER, nodeId), delivered)
            return codec.decode(KoraResponse.serializer(), codec.encode(KoraResponse.serializer(), answer))
        }

        override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = flow {
            val callee = target(peer)
            val delivered = codec.decode(SubscribeRequest.serializer(), codec.encode(SubscribeRequest.serializer(), request))
            emitAll(
                callee.subscribe(Caller(CallerKind.PEER, nodeId), delivered).map {
                    codec.decode(KoraEvent.serializer(), codec.encode(KoraEvent.serializer(), it))
                },
            )
        }

        override suspend fun callSending(peer: PeerInfo, request: KoraRequest, bytes: ByteStream): KoraResponse {
            val callee = streamTarget(peer)
            val delivered = transfer(KoraRequest.serializer(), request)
            return transfer(KoraResponse.serializer(), callee.handleSending(Caller(CallerKind.PEER, nodeId), delivered, bytes))
        }

        override suspend fun callReceiving(peer: PeerInfo, request: KoraRequest): StreamedResponse {
            val callee = streamTarget(peer)
            val delivered = transfer(KoraRequest.serializer(), request)
            val answer = callee.handleReceiving(Caller(CallerKind.PEER, nodeId), delivered)
            val response = try {
                transfer(KoraResponse.serializer(), answer.response)
            } catch (e: Throwable) {
                answer.close()
                throw e
            }
            return StreamedResponse(response, answer.bytes)
        }

        private fun target(peer: PeerInfo): KoraNode = synchronized(lock) { endpoints[peer.nodeId]?.node }
            ?: throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer '${peer.nodeId}' is not connected"))

        /** The node behind [peer] for a stream call; on a network without streams nothing is delivered. */
        private fun streamTarget(peer: PeerInfo): KoraNode {
            if (!streams) {
                throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "Peer '${peer.nodeId}' cannot take a byte stream"))
            }
            return target(peer)
        }

        /** [value] encoded with [codec] and decoded again, as it crosses the network. */
        private fun <T> transfer(serializer: KSerializer<T>, value: T): T =
            codec.decode(serializer, codec.encode(serializer, value))
    }
}
