package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [RoutingPeerTransport] streams only through a route whose transport streams, and `Target.Any` does not skip. */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRoutingTest {
    private val plainNetwork = InMemoryPeerNetwork()
    private val streamingNetwork = InMemoryPeerNetwork()
    private val plainStore = BlobStore()
    private val streamingStore = BlobStore()

    /** "a" is reached through a route whose transport is a plain [PeerTransport], "b" through a streaming one. */
    private fun connect() {
        val a = plainNetwork.endpoint("a")
        val b = streamingNetwork.endpoint("b")
        a.connect(KoraNode("a", peerDirectory = a, transport = a).also(plainStore::provideOn))
        b.connect(KoraNode("b", peerDirectory = b, transport = b).also(streamingStore::provideOn))
    }

    private val plainRoute = plainNetwork.endpoint("caller").let { PeerRoute(it, object : PeerTransport by it {}) }
    private val streamingRoute = streamingNetwork.endpoint("caller").let { PeerRoute(it, it) }
    private val routing = RoutingPeerTransport(listOf(plainRoute, streamingRoute))

    private fun peer(id: String) = (plainRoute.directory.peers.value + streamingRoute.directory.peers.value).single { it.nodeId == id }

    @Test
    fun `routing streams through a streaming route and refuses the others before sending`() = runTest {
        connect()
        val streamed = TrackedSource("abc")
        val refused = TrackedSource("abc")

        val sent = routing.callSending(peer("b"), request(Blobs.Put, "x"), ByteStream(3, streamed))
        val received = routing.callReceiving(peer("b"), request(Blobs.Get, "x"))
        val upload = thrownBy<KoraException> { routing.callSending(peer("a"), request(Blobs.Put, "x"), ByteStream(3, refused)) }
        val download = thrownBy<KoraException> { routing.callReceiving(peer("a"), request(Blobs.Get, "x")) }
        val plain = routing.call(peer("a"), request(Blobs.Drop, "x"))

        assertEquals(3L, sent.output(Blobs.Put))
        assertEquals("abc", received.bytes?.text())
        received.close()
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, upload.error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, download.error.code)
        assertEquals(3, refused.available(), "nothing was read from a refused upload")
        assertEquals(Unit, plain.output(Blobs.Drop))
        assertEquals(List(2) { Caller(CallerKind.PEER, "caller") }, streamingStore.callers)
        assertEquals(listOf(Caller(CallerKind.PEER, "caller")), plainStore.callers, "only the plain call reached 'a'")
    }

    @Test
    fun `any picks the first peer even when it cannot take the stream`() = runTest(UnconfinedTestDispatcher()) {
        connect()
        val directory = CompositePeerDirectory(listOf(plainRoute.directory, streamingRoute.directory), backgroundScope)
        val caller = KoraNode("caller", peerDirectory = directory, transport = routing)
        val refused = TrackedSource("abc")

        val failure = thrownBy<KoraException> { caller.callSending(Blobs.Put, "x", ByteStream(3, refused)) }
        val chosen = caller.callSending(Blobs.Put, "x", ByteStream(3, TrackedSource("abc")), Target.Peer("b"))

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, failure.error.code)
        assertTrue(refused.closed)
        assertEquals(3L, chosen)
        assertEquals(emptyList<Caller>(), plainStore.callers)
        assertEquals(mapOf("x" to "abc"), streamingStore.content)
    }
}
