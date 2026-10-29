package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PageRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerRoutesTest {
    private class FakeDirectory(vararg ids: String) : PeerDirectory {
        val list = MutableStateFlow(ids.map(::info))
        override val peers: StateFlow<List<PeerInfo>> = list
    }

    /** Answers every call with an `internal` failure whose message names the transport, so the route is visible. */
    private class NamedTransport(val name: String) : PeerTransport {
        val subscribed = mutableListOf<String>()

        override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse =
            KoraResponse.Failure(request.id, eus.bihurri.kora.contract.KoraError(ErrorCode.INTERNAL, "$name:${peer.nodeId}"))

        override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> {
            subscribed += peer.nodeId
            return emptyFlow()
        }
    }

    private companion object {
        fun info(id: String, vararg capabilities: CapabilityDescriptor) =
            PeerInfo(NodeDescriptor(id, id, capabilities.toList().ifEmpty { listOf(MediaLibrary.descriptor()) }))
    }

    @Test
    fun `the composite merges directories ordered by node id and follows them`() = runTest(UnconfinedTestDispatcher()) {
        val binder = FakeDirectory("sonora")
        val wear = FakeDirectory("wear:sonora", "wear:kardio")
        val composite = CompositePeerDirectory(listOf(binder, wear), backgroundScope)

        assertEquals(listOf("sonora", "wear:kardio", "wear:sonora"), composite.peers.value.map { it.nodeId })

        wear.list.value = emptyList()
        assertEquals(listOf("sonora"), composite.peers.value.map { it.nodeId })

        binder.list.value = listOf(info("koko"), info("sonora"))
        assertEquals(listOf("koko", "sonora"), composite.peers.value.map { it.nodeId })
    }

    @Test
    fun `the first directory wins when two list the same node`() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeDirectory().also { it.list.value = listOf(PeerInfo(NodeDescriptor("x", "first"))) }
        val second = FakeDirectory().also { it.list.value = listOf(PeerInfo(NodeDescriptor("x", "second"))) }

        val composite = CompositePeerDirectory(listOf(first, second), backgroundScope)

        assertEquals("first", composite.peers.value.single().descriptor.displayName)
    }

    @Test
    fun `an empty composite has no peers`() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(emptyList<PeerInfo>(), CompositePeerDirectory(emptyList(), backgroundScope).peers.value)
    }

    @Test
    fun `routing picks the transport of the directory that lists the peer`() = runTest {
        val binder = FakeDirectory("sonora")
        val wear = FakeDirectory("wear:sonora")
        val routing = RoutingPeerTransport(
            listOf(PeerRoute(binder, NamedTransport("binder")), PeerRoute(wear, NamedTransport("wear"))),
        )
        val request = request(MediaLibrary.ListPlaylists, PageRequest())

        assertEquals("binder:sonora", (routing.call(info("sonora"), request) as KoraResponse.Failure).error.message)
        assertEquals("wear:wear:sonora", (routing.call(info("wear:sonora"), request) as KoraResponse.Failure).error.message)
    }

    @Test
    fun `routing follows directory changes and refuses peers nobody lists`() = runTest {
        val wear = FakeDirectory("wear:sonora")
        val wearTransport = NamedTransport("wear")
        val routing = RoutingPeerTransport(listOf(PeerRoute(wear, wearTransport)))
        val request = request(MediaLibrary.ListPlaylists, PageRequest())

        wear.list.value = emptyList()

        val failure = runCatching { routing.call(info("wear:sonora"), request) }.exceptionOrNull()
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
        val streamFailure = runCatching { routing.subscribe(info("wear:sonora"), subscription(MediaPlayback.NowPlayingChanged)).collect {} }
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (streamFailure.exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `routing subscribes through the right transport`() = runTest {
        val binder = NamedTransport("binder")
        val wear = NamedTransport("wear")
        val routing = RoutingPeerTransport(
            listOf(PeerRoute(FakeDirectory("a"), binder), PeerRoute(FakeDirectory("wear:b"), wear)),
        )

        routing.subscribe(info("wear:b"), subscription(MediaPlayback.NowPlayingChanged)).collect {}
        routing.subscribe(info("a"), subscription(MediaPlayback.NowPlayingChanged)).collect {}

        assertEquals(listOf("wear:b"), wear.subscribed)
        assertEquals(listOf("a"), binder.subscribed)
    }

    @Test
    fun `a node over a composite reaches peers of both routes`() = runTest(UnconfinedTestDispatcher()) {
        val binder = FakeDirectory("sonora")
        val wear = FakeDirectory("wear:sonora")
        val routes = listOf(PeerRoute(binder, NamedTransport("binder")), PeerRoute(wear, NamedTransport("wear")))
        val node = KoraNode(
            "watch",
            peerDirectory = CompositePeerDirectory(routes.map { it.directory }, backgroundScope),
            transport = RoutingPeerTransport(routes),
        )

        assertEquals(listOf("sonora", "wear:sonora"), node.peers.value.map { it.nodeId })
        assertEquals(listOf("sonora", "wear:sonora"), node.providers(MediaLibrary))
        val failure = runCatching { node.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("wear:sonora")) }
        assertEquals(ErrorCode.INTERNAL, (failure.exceptionOrNull() as KoraException).error.code)
        assertEquals("wear:wear:sonora", (failure.exceptionOrNull() as KoraException).error.message)
    }
}
