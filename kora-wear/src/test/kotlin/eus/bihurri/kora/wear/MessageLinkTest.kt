package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.Target
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** A fake Data Layer: records messages, lets the test choose who is reachable and hand messages to the other end. */
private class FakeDataLayer : DataLayer {
    var nodes: List<DataNode> = emptyList()
    var failLookup = false
    var failSend = false
    var lookups = 0
    var listenerRegistrations = 0
    val sent = mutableListOf<Triple<String, String, ByteArray>>()
    var onCapabilityChange: (() -> Unit)? = null

    /** Called for every accepted message, to hand it to the other device's link. */
    var transport: (String, String, ByteArray) -> Unit = { _, _, _ -> }

    override suspend fun reachableNodes(): List<DataNode> {
        lookups++
        if (failLookup) throw IllegalStateException("no play services")
        return nodes
    }

    override suspend fun send(nodeId: String, path: String, data: ByteArray) {
        if (failSend) throw java.io.IOException("TARGET_NODE_NOT_CONNECTED")
        sent += Triple(nodeId, path, data)
        transport(nodeId, path, data)
    }

    override fun addCapabilityListener(onChange: () -> Unit): AutoCloseable {
        listenerRegistrations++
        onCapabilityChange = onChange
        return AutoCloseable { onCapabilityChange = null }
    }

    fun capabilityChanged(newNodes: List<DataNode>) {
        nodes = newNodes
        onCapabilityChange?.invoke()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessageLinkTest {
    private val watch = DataNode("watch-1", isNearby = true)

    private fun TestScope.link(layer: FakeDataLayer, chunk: Int = FrameChunks.DEFAULT_CHUNK_PAYLOAD) =
        MessageLink(layer, backgroundScope, chunk)

    @Test
    fun `nothing touches the data layer until the link is used`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer()
        val link = link(layer)

        assertEquals(0, layer.lookups)
        assertEquals(0, layer.listenerRegistrations)
        assertFalse(link.reachable.value)
    }

    @Test
    fun `the first send resolves the counterpart once and registers one capability listener`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val link = link(layer)

        link.send(byteArrayOf(1))
        link.send(byteArrayOf(2))

        assertEquals(1, layer.lookups)
        assertEquals(1, layer.listenerRegistrations)
        assertEquals(listOf("watch-1", "watch-1"), layer.sent.map { it.first })
        assertEquals(WearProtocol.PATH_FRAME, layer.sent.first().second)
        assertTrue(link.reachable.value)
    }

    @Test
    fun `a nearby device wins, then the lowest id`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(DataNode("b", false), DataNode("z", true), DataNode("a", false), DataNode("y", true)) }
        link(layer).send(byteArrayOf(1))
        assertEquals("y", layer.sent.single().first)

        val onlyFar = FakeDataLayer().apply { nodes = listOf(DataNode("b", false), DataNode("a", false)) }
        link(onlyFar).send(byteArrayOf(1))
        assertEquals("a", onlyFar.sent.single().first)
    }

    @Test
    fun `without a paired device send is unavailable and the link is not reachable`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer()
        val link = link(layer)

        val failure = runCatching { link.send(byteArrayOf(1)) }.exceptionOrNull()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
        assertFalse(link.reachable.value)
        assertTrue(layer.sent.isEmpty())
    }

    @Test
    fun `a failing lookup is unavailable, not a crash`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { failLookup = true }

        val failure = runCatching { link(layer).send(byteArrayOf(1)) }.exceptionOrNull()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
    }

    @Test
    fun `a failed send is unavailable and the next send looks the device up again`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val link = link(layer)
        link.send(byteArrayOf(1))

        layer.failSend = true
        val failure = runCatching { link.send(byteArrayOf(2)) }.exceptionOrNull()
        layer.failSend = false
        link.send(byteArrayOf(3))

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
        assertEquals(2, layer.lookups)
        assertEquals(2, layer.sent.size)
    }

    @Test
    fun `a large frame goes out as several messages under the limit`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val frame = Random(1).nextBytes(WearProtocol.MAX_FRAME_BYTES)

        link(layer).send(frame)

        assertEquals(5, layer.sent.size)
        assertTrue(layer.sent.all { it.third.size < 100_000 })
    }

    @Test
    fun `capability changes update reachability and a new lookup`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val link = link(layer)
        link.send(byteArrayOf(1))
        assertTrue(link.reachable.value)

        layer.capabilityChanged(emptyList())
        assertFalse(link.reachable.value)

        layer.capabilityChanged(listOf(watch))
        assertTrue(link.reachable.value)
    }

    @Test
    fun `frames from the counterpart arrive, reassembled, and everything else is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val link = link(layer, chunk = 100)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.incoming.collect { received += it } }
        val frame = Random(2).nextBytes(250)
        val chunks = FrameChunks.split(frame, 100)

        link.deliver("stranger", WearProtocol.PATH_FRAME, FrameChunks.split(byteArrayOf(9)).single())
        link.deliver("watch-1", "/other/path", FrameChunks.split(byteArrayOf(8)).single())
        link.deliver("watch-1", WearProtocol.PATH_FRAME, byteArrayOf(0, 1)) // junk
        chunks.reversed().forEach { link.deliver("watch-1", WearProtocol.PATH_FRAME, it) }

        assertEquals(1, received.size)
        assertArrayEquals(frame, received.single())
    }

    @Test
    fun `an incoming message resolves the counterpart if nothing was sent yet`() = runTest(UnconfinedTestDispatcher()) {
        val layer = FakeDataLayer().apply { nodes = listOf(watch) }
        val link = link(layer)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { link.incoming.collect { received += it } }

        link.deliver("watch-1", WearProtocol.PATH_FRAME, FrameChunks.split(byteArrayOf(5)).single())

        assertEquals(1, received.size)
        assertTrue(link.reachable.value)
    }

    @Test
    fun `end to end a watch reads a large answer from sonora through the phone`() = runTest(UnconfinedTestDispatcher()) {
        val watchLayer = FakeDataLayer().apply { nodes = listOf(DataNode("phone-1", true)) }
        val phoneLayer = FakeDataLayer().apply { nodes = listOf(watch) }
        val watchLink = MessageLink(watchLayer, backgroundScope)
        val phoneLink = MessageLink(phoneLayer, backgroundScope)
        watchLayer.transport = { _, path, data -> phoneLink.deliver("watch-1", path, data) }
        phoneLayer.transport = { _, path, data -> watchLink.deliver("phone-1", path, data) }

        val network = InMemoryPeerNetwork()
        val sonoraEndpoint = network.endpoint("sonora")
        val phoneEndpoint = network.endpoint("kardio")
        val bigName = "x".repeat(150_000)
        val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { Page(listOf(PlaylistSummary("p", bigName, 1)), 1, 0) } }
        }
        val phone = KoraNode("kardio", peerDirectory = phoneEndpoint, transport = phoneEndpoint)
        val phoneRelay = WearRelay(phoneLink, backgroundScope)
        val watchRelay = WearRelay(watchLink, backgroundScope)
        val watchNode = KoraNode("kardio", peerDirectory = watchRelay.directory, transport = watchRelay.transport)
        sonoraEndpoint.connect(sonora)
        phoneEndpoint.connect(phone)
        phoneRelay.serve(phone, Caller(CallerKind.PEER, "wear:kardio"))
        watchRelay.serve(watchNode, Caller(CallerKind.PEER, "wear:kardio"))

        assertTrue(watchRelay.refresh())
        val page = watchNode.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("wear:sonora"))

        assertEquals(bigName, page.items.single().name)
        assertTrue(phoneLayer.sent.size > 2) { "the answer needed several messages: ${phoneLayer.sent.size}" }

        // The phone goes away: the capability disappears and the watch notices without any traffic.
        watchLayer.capabilityChanged(emptyList())
        assertEquals(emptyList<String>(), watchNode.peers.value.map { it.nodeId })
    }
}
