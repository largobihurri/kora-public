package eus.bihurri.kora.core

import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.MediaVolume
import eus.bihurri.kora.contract.media.VolumeState
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NodeAwaitProviderTest {
    private val network = InMemoryPeerNetwork()
    private val watchEndpoint = network.endpoint("watch")
    private val sonoraEndpoint = network.endpoint("sonora")
    private val watch = KoraNode("watch", peerDirectory = watchEndpoint, transport = watchEndpoint)
    private val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
        provideStubbed(MediaVolume) { on(MediaVolume.GetVolume) { VolumeState(3, 15) } }
    }

    @Test
    fun `answers true at once when a provider is already available`() = runTest {
        watchEndpoint.connect(watch)
        sonoraEndpoint.connect(sonora)

        assertTrue(watch.awaitProvider(MediaVolume, 5.seconds))
        assertTrue(watch.awaitProvider(MediaVolume, Duration.ZERO))
        assertEquals(0, currentTime)
    }

    @Test
    fun `waits until a peer that provides the capability appears`() = runTest {
        watchEndpoint.connect(watch)
        val waiting = async { watch.awaitProvider(MediaVolume, 5.seconds) }
        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(waiting.isCompleted)

        sonoraEndpoint.connect(sonora)

        assertTrue(waiting.await())
        assertEquals(2_000, currentTime)
        assertEquals(VolumeState(3, 15), watch.call(MediaVolume.GetVolume, NoInput))
    }

    @Test
    fun `waits until a local provider registers`() = runTest {
        val node = KoraNode("standalone")
        val waiting = async { node.awaitProvider(MediaPlayback, 5.seconds) }
        advanceTimeBy(1.seconds)

        node.provideStubbed(MediaPlayback) {}

        assertTrue(waiting.await())
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `answers false when no provider appears within the timeout`() = runTest {
        watchEndpoint.connect(watch)

        assertFalse(watch.awaitProvider(MediaVolume, 5.seconds))
        assertEquals(5_000, currentTime)
        assertFalse(watch.awaitProvider(MediaVolume, Duration.ZERO))
        assertFalse(watch.awaitProvider(MediaVolume, (-1).seconds))
        assertEquals(5_000, currentTime)
    }

    @Test
    fun `a peer target waits for that peer even when this node provides the capability`() = runTest {
        watch.provideStubbed(MediaVolume) { on(MediaVolume.GetVolume) { VolumeState(0, 15) } }
        watchEndpoint.connect(watch)
        assertTrue(watch.awaitProvider(MediaVolume, Duration.ZERO), "the local provider counts for Target.Any")

        val waiting = async { watch.awaitProvider(MediaVolume, 5.seconds, Target.Peer("sonora")) }
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(waiting.isCompleted)

        sonoraEndpoint.connect(sonora)

        assertTrue(waiting.await())
        assertEquals(1_000, currentTime)
        assertEquals(VolumeState(3, 15), watch.call(MediaVolume.GetVolume, NoInput, Target.Peer("sonora")))
    }

    @Test
    fun `a local target ignores peers and an unknown peer target times out`() = runTest {
        watchEndpoint.connect(watch)
        sonoraEndpoint.connect(sonora)

        assertFalse(watch.awaitProvider(MediaVolume, 1.seconds, Target.Local))
        assertFalse(watch.awaitProvider(MediaVolume, 1.seconds, Target.Peer("nobody")))
        assertTrue(watch.awaitProvider(MediaVolume, Duration.ZERO, Target.Peer("sonora")))
    }
}
