package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.core.PeerDirectory
import eus.bihurri.kora.core.PeerInfo
import eus.bihurri.kora.core.PeerRoute
import eus.bihurri.kora.core.PeerTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** `KoraAndroid.install(peerRoutes = ...)`: the node lists and reaches the peers of extra routes next to Binder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KoraAndroidRoutesTest {
    @After
    fun tearDown() {
        KoraAndroid.uninstall()
    }

    private class FakeRoute : PeerDirectory, PeerTransport {
        val list = MutableStateFlow(listOf(PeerInfo(NodeDescriptor("wear:sonora", "Sonora", listOf(CapabilityDescriptor("media.library", MediaLibrary.version))))))
        val called = mutableListOf<String>()
        override val peers: StateFlow<List<PeerInfo>> = list

        override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
            called += peer.nodeId
            return KoraResponse.Success(request.id, ByteArray(0))
        }

        override fun subscribe(peer: PeerInfo, request: SubscribeRequest): Flow<KoraEvent> = emptyFlow()
    }

    private fun eventually(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            Thread.sleep(20)
        }
        assertTrue("condition not reached in time", check())
    }

    @Test
    fun `an extra route's peers are listed and reachable through the node`() {
        val route = FakeRoute()

        val node = KoraAndroid.install(RuntimeEnvironment.getApplication(), peerRoutes = listOf(PeerRoute(route, route)))

        eventually { node.peers.value.any { it.nodeId == "wear:sonora" } }
        assertTrue(node.isAvailable(MediaLibrary))
        assertEquals(listOf("wear:sonora"), node.providers(MediaLibrary))
    }

    @Test
    fun `without extra routes the node keeps the plain Binder directory`() {
        val node = KoraAndroid.install(RuntimeEnvironment.getApplication())

        assertEquals(emptyList<String>(), node.peers.value.map { it.nodeId })
    }
}
