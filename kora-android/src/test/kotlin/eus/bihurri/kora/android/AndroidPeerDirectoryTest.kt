package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CapabilityDescriptor
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.NodeDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeerDirectoryTest {
    private class Fixture {
        val installed = mutableListOf<PeerCandidate>()
        val fetches = mutableListOf<String>()
        var descriptors = mutableMapOf<String, NodeDescriptor>()
        var failing = mutableSetOf<String>()
        var hanging = mutableSetOf<String>()
        var notify: (() -> Unit)? = null
        var closedChanges = false

        val scanner = PeerScanner { installed.toList() }
        val fetcher = DescriptorFetcher { pkg ->
            fetches += pkg
            if (pkg in hanging) delay(Long.MAX_VALUE)
            if (pkg in failing) throw IllegalStateException("cannot bind")
            descriptors.getValue(pkg)
        }
        val changes = PackageChangeSource { onChange ->
            notify = onChange
            AutoCloseable { closedChanges = true }
        }
    }

    private val f = Fixture()

    private fun descriptor(pkg: String, nodeId: String = pkg, vararg capabilities: String) =
        NodeDescriptor(nodeId, "App $pkg", capabilities.map { CapabilityDescriptor(it, CapabilityVersion(1, 0)) })

    private fun TestScope.directory() =
        AndroidPeerDirectory(f.scanner, f.fetcher, f.changes, backgroundScope, fetchTimeout = 5.seconds, debounce = 250.milliseconds)

    private fun peerIds(directory: AndroidPeerDirectory) = directory.peers.value.map { it.nodeId }

    @Test
    fun `starting scans and publishes the peers sorted by package`() = runTest {
        f.installed += listOf(PeerCandidate("b.app", "1"), PeerCandidate("a.app", "1"))
        f.descriptors["a.app"] = descriptor("a.app", capabilities = arrayOf("media.library"))
        f.descriptors["b.app"] = descriptor("b.app")
        val directory = directory()

        directory.start()
        advanceTimeBy(300.milliseconds)
        runCurrent()

        assertEquals(listOf("a.app", "b.app"), peerIds(directory))
        assertEquals(listOf("media.library"), directory.peers.value[0].descriptor.capabilities.map { it.id })
    }

    @Test
    fun `the node id is always the package name`() = runTest {
        f.installed += PeerCandidate("a.app", "1")
        f.descriptors["a.app"] = descriptor("a.app", nodeId = "something-else")
        val directory = directory()

        directory.refresh()

        assertEquals(listOf("a.app"), peerIds(directory))
    }

    @Test
    fun `an unchanged package is not fetched again`() = runTest {
        f.installed += PeerCandidate("a.app", "1")
        f.descriptors["a.app"] = descriptor("a.app")
        val directory = directory()

        directory.refresh()
        directory.refresh()

        assertEquals(listOf("a.app"), f.fetches)
    }

    @Test
    fun `an updated package is fetched again and its new descriptor replaces the old one`() = runTest {
        f.installed += PeerCandidate("a.app", "1")
        f.descriptors["a.app"] = descriptor("a.app")
        val directory = directory()
        directory.refresh()

        f.installed[0] = PeerCandidate("a.app", "2")
        f.descriptors["a.app"] = descriptor("a.app", capabilities = arrayOf("location"))
        directory.refresh()

        assertEquals(2, f.fetches.size)
        assertEquals(listOf("location"), directory.peers.value.single().descriptor.capabilities.map { it.id })
    }

    @Test
    fun `a removed package disappears and is fetched again if it comes back`() = runTest {
        f.installed += PeerCandidate("a.app", "1")
        f.descriptors["a.app"] = descriptor("a.app")
        val directory = directory()
        directory.refresh()

        f.installed.clear()
        directory.refresh()
        assertTrue(directory.peers.value.isEmpty())

        f.installed += PeerCandidate("a.app", "1")
        directory.refresh()
        assertEquals(listOf("a.app"), peerIds(directory))
        assertEquals(2, f.fetches.size)
    }

    @Test
    fun `a peer that cannot be described is left out and retried on the next scan`() = runTest {
        f.installed += listOf(PeerCandidate("bad.app", "1"), PeerCandidate("ok.app", "1"))
        f.descriptors["ok.app"] = descriptor("ok.app")
        f.failing += "bad.app"
        val directory = directory()

        directory.refresh()
        assertEquals(listOf("ok.app"), peerIds(directory))

        f.failing.clear()
        f.descriptors["bad.app"] = descriptor("bad.app")
        directory.refresh()
        assertEquals(listOf("bad.app", "ok.app"), peerIds(directory))
    }

    @Test
    fun `a peer that never answers is left out after the fetch timeout`() = runTest {
        f.installed += listOf(PeerCandidate("slow.app", "1"), PeerCandidate("ok.app", "1"))
        f.descriptors["ok.app"] = descriptor("ok.app")
        f.hanging += "slow.app"
        val directory = directory()

        directory.refresh()

        assertEquals(listOf("ok.app"), peerIds(directory))
    }

    @Test
    fun `package broadcasts trigger one debounced rescan`() = runTest {
        f.descriptors["a.app"] = descriptor("a.app")
        val directory = directory()
        directory.start()
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertTrue(directory.peers.value.isEmpty())

        f.installed += PeerCandidate("a.app", "1")
        repeat(5) { f.notify!!.invoke() }
        advanceTimeBy(300.milliseconds)
        runCurrent()

        assertEquals(listOf("a.app"), peerIds(directory))
        assertEquals(1, f.fetches.size)
    }

    @Test
    fun `closing stops watching package changes`() = runTest {
        val directory = directory()
        directory.start()
        advanceTimeBy(300.milliseconds)

        directory.close()

        assertTrue(f.closedChanges)
    }
}
