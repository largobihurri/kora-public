package eus.bihurri.kora.wear

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.location.LocationCapability
import eus.bihurri.kora.contract.location.LocationFix
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.AuditEntry
import eus.bihurri.kora.core.AuditKind
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.CallerPolicy
import eus.bihurri.kora.core.CompositePeerDirectory
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.PeerRoute
import eus.bihurri.kora.core.RoutingPeerTransport
import eus.bihurri.kora.core.Target
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import eus.bihurri.kora.wear.testing.InMemoryWireLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A watch node and a phone node of the same app (same node id, like two installs of one applicationId), connected by
 * two in-memory wire links. The phone has a Binder-like peer "sonora" providing media; the watch reaches it only
 * through the relay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearRelayTest {
    private val track = TrackSummary("t1", "Title", "Artist")
    private val playlists = Page(listOf(PlaylistSummary("p1", "Morning run", 12)), total = 1, offset = 0)
    private val nowPlaying = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 8)
    private var nowPlayingClosed = 0
    private val sonoraAudit = Recorder()
    private val phoneAudit = Recorder()
    private val seeks = mutableListOf<Pair<Long, Caller>>()
    private var listPlaylists: suspend () -> Page<PlaylistSummary> = { playlists }

    private class Recorder : AuditLog {
        val entries = mutableListOf<AuditEntry>()

        override fun record(entry: AuditEntry) {
            entries += entry
        }
    }

    private val phoneNetwork = InMemoryPeerNetwork()
    private val sonoraEndpoint = phoneNetwork.endpoint("sonora")
    private val phoneEndpoint = phoneNetwork.endpoint("kardio")

    private val sonora = KoraNode("sonora", audit = sonoraAudit, peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
        provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { listPlaylists() } }
        provideStubbed(MediaPlayback) {
            on(MediaPlayback.SeekTo) { seeks += it.positionMs to caller }
            on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.PLAYING, 1_000, 1, track) }
            emits(MediaPlayback.NowPlayingChanged) { nowPlaying.onCompletion { nowPlayingClosed++ } }
        }
    }

    private var phonePolicy: CallerPolicy = CallerPolicy { _, _ -> true }
    private val fix = LocationFix(43.26, -2.93, timeEpochMs = 99)

    private val phone = KoraNode(
        "kardio",
        audit = phoneAudit,
        policy = { caller, scope -> phonePolicy.authorize(caller, scope) },
        peerDirectory = phoneEndpoint,
        transport = phoneEndpoint,
    ).apply {
        provideStubbed(LocationCapability) { on(LocationCapability.GetLastFix) { fix } }
    }

    private val links = InMemoryWireLink.pair()
    private val watchLink = links.first
    private val phoneLink = links.second
    private val counterpart = Caller(CallerKind.PEER, "wear:kardio")

    private fun TestScope.newRig(callTimeout: Duration = 5.seconds, relayTimeout: Duration = 10.seconds): Rig {
        val scope = backgroundScope
        val phoneRelay = WearRelay(phoneLink, scope)
        val watchRelay = WearRelay(watchLink, scope, callTimeout = relayTimeout, describeTimeout = relayTimeout)
        val watch = KoraNode(
            "kardio",
            peerDirectory = watchRelay.directory,
            transport = watchRelay.transport,
            callTimeout = callTimeout,
        )
        sonoraEndpoint.connect(sonora)
        phoneEndpoint.connect(phone)
        val server = phoneRelay.serve(phone, counterpart)
        watchRelay.serve(watch, Caller(CallerKind.PEER, "wear:kardio"))
        return Rig(watch, watchRelay, phoneRelay, server)
    }

    private class Rig(val watch: KoraNode, val watchRelay: WearRelay, val phoneRelay: WearRelay, val server: RelayServer)

    @Test
    fun `nothing is sent until the watch asks for the phone's peers`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()

        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })
        assertTrue(watchLink.sent.isEmpty())
        assertTrue(phoneLink.sent.isEmpty())
    }

    @Test
    fun `refresh lists the phone app and its peers with prefixed ids`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()

        assertTrue(rig.watchRelay.refresh())

        assertEquals(listOf("wear:kardio", "wear:sonora"), rig.watch.peers.value.map { it.nodeId })
        assertEquals(listOf("wear:kardio", "wear:sonora"), rig.watch.peers.value.map { it.descriptor.nodeId })
        assertEquals(listOf("wear:sonora"), rig.watch.providers(MediaLibrary))
        assertEquals(listOf("wear:kardio"), rig.watch.providers(LocationCapability))
    }

    @Test
    fun `the watch lists playlists of sonora through the phone`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()

        val page = rig.watch.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("wear:sonora"))
        val anyTarget = rig.watch.call(MediaLibrary.ListPlaylists, PageRequest())

        assertEquals(playlists, page)
        assertEquals(playlists, anyTarget)
        // Sonora sees the phone app (its Binder peer), the phone's policy and audit saw the counterpart.
        assertEquals(Caller(CallerKind.PEER, "kardio"), sonoraAudit.entries.first().caller)
        assertEquals(counterpart, phoneAudit.entries.first().caller)
    }

    @Test
    fun `the watch calls the phone app itself`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()

        assertEquals(fix, rig.watch.call(LocationCapability.GetLastFix, NoInput))
    }

    @Test
    fun `the watch controls sonora and the phone policy still applies`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()

        rig.watch.call(MediaPlayback.SeekTo, SeekRequest(42_000))
        assertEquals(listOf(42_000L), seeks.map { it.first })

        phonePolicy = CallerPolicy { _, scope -> scope != MediaPlayback.ControlScope }
        val failure = runCatching { rig.watch.call(MediaPlayback.SeekTo, SeekRequest(1)) }.exceptionOrNull()

        assertEquals(ErrorCode.UNAUTHORIZED, (failure as KoraException).error.code)
        assertEquals(1, seeks.size)
        assertEquals(1, sonoraAudit.entries.count { it.member == "seek_to" })
    }

    @Test
    fun `a handler error of sonora reaches the watch`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        listPlaylists = { throw KoraException(eus.bihurri.kora.contract.KoraError(ErrorCode.UNAUTHORIZED, "nope")) }

        val failure = runCatching { rig.watch.call(MediaLibrary.ListPlaylists, PageRequest()) }.exceptionOrNull()

        assertEquals(ErrorCode.UNAUTHORIZED, (failure as KoraException).error.code)
    }

    @Test
    fun `the watch receives now playing events and cancelling stops sonora's stream`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        val received = mutableListOf<NowPlaying>()

        val job = launch { rig.watch.events(MediaPlayback.NowPlayingChanged, Target.Peer("wear:sonora")).collect { received += it } }
        assertEquals(1, rig.server.activeSubscriptions)
        nowPlaying.emit(NowPlaying(track, 1))
        nowPlaying.emit(NowPlaying(TrackSummary("t2", "Next"), 2))

        assertEquals(listOf(1L, 2L), received.map { it.startedAtEpochMs })
        assertEquals(Caller(CallerKind.PEER, "kardio"), sonoraAudit.entries.single { it.kind == AuditKind.SUBSCRIBE }.caller)
        assertEquals(0, nowPlayingClosed)

        job.cancel()
        job.join()

        assertEquals(0, rig.server.activeSubscriptions)
        assertEquals(1, nowPlayingClosed)
    }

    @Test
    fun `a refused subscription fails the collector`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        phonePolicy = CallerPolicy { _, scope -> scope != MediaPlayback.ReadScope }

        val failure = runCatching { rig.watch.events(MediaPlayback.NowPlayingChanged).collect {} }.exceptionOrNull()

        assertEquals(ErrorCode.UNAUTHORIZED, (failure as KoraException).error.code)
        assertEquals(0, rig.server.activeSubscriptions)
    }

    @Test
    fun `peers changed on the phone are pushed once the watch asked`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        assertEquals(listOf("wear:kardio", "wear:sonora"), rig.watch.peers.value.map { it.nodeId })

        sonoraEndpoint.disconnect()

        assertEquals(listOf("wear:kardio"), rig.watch.peers.value.map { it.nodeId })
        assertFalse(rig.watch.isAvailable(MediaLibrary))

        sonoraEndpoint.connect(sonora)

        assertTrue(rig.watch.isAvailable(MediaLibrary))
    }

    @Test
    fun `the phone pushes nothing before the watch asked`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()

        sonoraEndpoint.disconnect()
        sonoraEndpoint.connect(sonora)

        assertTrue(phoneLink.sent.isEmpty())
        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })
    }

    @Test
    fun `link loss fails the call in flight and ends the stream`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        val release = CompletableDeferred<Page<PlaylistSummary>>()
        listPlaylists = { release.await() }
        val call = async { runCatching { rig.watch.call(MediaLibrary.ListPlaylists, PageRequest()) } }
        var streamFailure: Throwable? = null
        launch { streamFailure = runCatching { rig.watch.events(MediaPlayback.NowPlayingChanged).collect {} }.exceptionOrNull() }
        assertEquals(1, rig.server.activeSubscriptions)

        watchLink.setConnected(false)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, ((call.await().exceptionOrNull()) as KoraException).error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (streamFailure as KoraException).error.code)
        assertEquals(0, rig.server.activeSubscriptions) // the phone released sonora's stream too
        assertEquals(1, nowPlayingClosed)
        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })
        release.complete(playlists)
    }

    @Test
    fun `after a loss the directory asks again by itself when the link returns`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        watchLink.setConnected(false)
        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })

        watchLink.setConnected(true)
        advanceUntilIdle() // the background refresh runs after the reconnection is handled

        assertEquals(listOf("wear:kardio", "wear:sonora"), rig.watch.peers.value.map { it.nodeId })
    }

    @Test
    fun `a directory that never asked stays silent when the link returns`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        watchLink.setConnected(false)
        watchLink.setConnected(true)

        assertTrue(watchLink.sent.isEmpty())
        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })
    }

    @Test
    fun `refresh on an unreachable phone reports it and lists nothing`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        watchLink.setConnected(false)

        assertFalse(rig.watchRelay.refresh())
        assertEquals(emptyList<String>(), rig.watch.peers.value.map { it.nodeId })
    }

    @Test
    fun `a call the phone never answers times out with virtual time`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(callTimeout = 60.seconds, relayTimeout = 10.seconds)
        rig.watchRelay.refresh()
        watchLink.dropOutgoing = true

        val call = async { runCatching { rig.watch.call(MediaLibrary.ListPlaylists, PageRequest()) } }
        advanceTimeBy(9.seconds)
        assertTrue(call.isActive)
        advanceTimeBy(2.seconds)

        assertEquals(ErrorCode.TIMEOUT, (call.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `the node's own call timeout also applies`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(callTimeout = 2.seconds, relayTimeout = 10.seconds)
        rig.watchRelay.refresh()
        watchLink.dropOutgoing = true

        val call = async { runCatching { rig.watch.call(MediaLibrary.ListPlaylists, PageRequest()) } }
        advanceTimeBy(3.seconds)

        assertEquals(ErrorCode.TIMEOUT, (call.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `a refresh nobody answers gives up after the describe timeout`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        watchLink.dropOutgoing = true

        val refresh = async { rig.watchRelay.refresh() }
        advanceTimeBy(11.seconds)

        assertFalse(refresh.await())
    }

    @Test
    fun `an oversized answer becomes payload_too_large`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        val big = "x".repeat(WearProtocol.MAX_FRAME_BYTES)
        listPlaylists = { Page(listOf(PlaylistSummary("p", big, 1)), 1, 0) }

        val failure = runCatching { rig.watch.call(MediaLibrary.ListPlaylists, PageRequest()) }.exceptionOrNull()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, (failure as KoraException).error.code)
    }

    @Test
    fun `an oversized request is refused before it is sent`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        val sentBefore = watchLink.sent.size
        val huge = KoraRequest("big", "media.library", MediaLibrary.version, "list_playlists", ByteArray(WearProtocol.MAX_FRAME_BYTES))

        val failure = runCatching { rig.watchRelay.transport.call(rig.watch.peers.value.first { it.nodeId == "wear:sonora" }, huge) }.exceptionOrNull()

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, (failure as KoraException).error.code)
        assertEquals(sentBefore, watchLink.sent.size)
    }

    // ---- What the phone refuses to relay ----------------------------------------------------------------------

    private fun raw(epoch: Long, body: FrameBody) = FrameCodec.encode(epoch, body)

    private suspend fun TestScope.exchange(frame: ByteArray): List<FrameBody> {
        val seen = mutableListOf<FrameBody>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            watchLink.incoming.collect { bytes -> (FrameCodec.read(bytes) as? FrameReading.Valid)?.let { seen += it.frame.body } }
        }
        watchLink.send(frame)
        collector.cancel()
        return seen
    }

    @Test
    fun `a request for a peer of the counterpart itself is not relayed back`() = runTest(UnconfinedTestDispatcher()) {
        newRig()
        val request = KoraRequest("r", "media.library", MediaLibrary.version, "list_playlists", CborCodec.encode(PageRequest.serializer(), PageRequest()))

        val answers = exchange(raw(1, FrameBody.Call("wear:sonora", request)))

        val response = (answers.single() as FrameBody.CallResult).response
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, assertInstanceOf(KoraResponse.Failure::class.java, response).error.code)
        assertTrue(sonoraAudit.entries.isEmpty())
    }

    @Test
    fun `a request for an unknown node is unavailable`() = runTest(UnconfinedTestDispatcher()) {
        newRig()
        val request = KoraRequest("r", "media.library", MediaLibrary.version, "list_playlists", CborCodec.encode(PageRequest.serializer(), PageRequest()))

        val answers = exchange(raw(1, FrameBody.Call("ghost", request)))

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (answers.single() as FrameBody.CallResult).response.let { (it as KoraResponse.Failure).error.code })
    }

    @Test
    fun `identity never comes from the frame, only the configured counterpart serves`() = runTest(UnconfinedTestDispatcher()) {
        newRig()
        phonePolicy = CallerPolicy { caller, _ -> caller == counterpart }
        val request = KoraRequest("r", "location", LocationCapability.version, "get_last_fix", CborCodec.encode(NoInput.serializer(), NoInput))

        val answers = exchange(raw(1, FrameBody.Call("kardio", request)))

        assertInstanceOf(KoraResponse.Success::class.java, (answers.single() as FrameBody.CallResult).response)
        assertEquals(counterpart, phoneAudit.entries.single().caller)
    }

    @Test
    fun `a counterpart restart cancels what it had subscribed`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        val subscribe = FrameBody.Subscribe("sonora", eus.bihurri.kora.contract.SubscribeRequest("old-1", "media.playback", MediaPlayback.version, "now_playing_changed"))
        watchLink.send(raw(1, subscribe))
        assertEquals(1, rig.server.activeSubscriptions)
        assertEquals(0, nowPlayingClosed)

        watchLink.send(raw(2, FrameBody.DescribePeers("d"))) // same app, new process: other epoch

        assertEquals(0, rig.server.activeSubscriptions)
        assertEquals(1, nowPlayingClosed)
    }

    @Test
    fun `the phone restarting ends the watch's streams`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig()
        rig.watchRelay.refresh()
        var failure: Throwable? = null
        launch { failure = runCatching { rig.watch.events(MediaPlayback.NowPlayingChanged).collect {} }.exceptionOrNull() }

        phoneLink.send(raw(999, FrameBody.PeersChanged(emptyList()))) // a frame from a new phone process

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
    }

    @Test
    fun `junk and frames of other versions are dropped without harm`() = runTest(UnconfinedTestDispatcher()) {
        val dropped = mutableListOf<String>()
        val scope = backgroundScope
        val phoneRelay = WearRelay(phoneLink, scope, onDropped = { dropped += it })
        phoneEndpoint.connect(phone)
        phoneRelay.serve(phone, counterpart)

        watchLink.send(byteArrayOf(1, 2, 3))
        watchLink.send(CborCodec.encode(Frame.serializer(), Frame(99, 1, FrameBody.Cancel("x"))))
        watchLink.send(raw(1, FrameBody.DescribePeers("d1")))

        assertEquals(2, dropped.size)
        assertTrue(dropped[1].contains("99"))
        assertEquals(1, phoneLink.sent.size) // and the valid one was still answered
    }

    @Test
    fun `the subscription cap is enforced`() = runTest(UnconfinedTestDispatcher()) {
        val framed = FramedLink(phoneLink, backgroundScope)
        phoneEndpoint.connect(phone)
        sonoraEndpoint.connect(sonora)
        val server = RelayServer(phone, framed, backgroundScope, counterpart, maxSubscriptions = 1)
        framed.start()
        fun sub(id: String) = raw(1, FrameBody.Subscribe("sonora", eus.bihurri.kora.contract.SubscribeRequest(id, "media.playback", MediaPlayback.version, "now_playing_changed")))

        val answers = mutableListOf<FrameBody>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            watchLink.incoming.collect { bytes -> (FrameCodec.read(bytes) as? FrameReading.Valid)?.let { answers += it.frame.body } }
        }
        watchLink.send(sub("a"))
        watchLink.send(sub("b"))
        watchLink.send(sub("a"))

        assertEquals(1, server.activeSubscriptions)
        val refused = answers.filterIsInstance<FrameBody.Failed>()
        assertEquals(listOf("b", "a"), refused.map { it.subscriptionId })
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, refused.first().error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, refused.last().error.code) // at the cap, so refused before the duplicate check
    }

    // ---- A watch with on-device peers as well -----------------------------------------------------------------

    @Test
    fun `a watch node sees on-device peers and phone peers and routes each correctly`() = runTest(UnconfinedTestDispatcher()) {
        val watchNetwork = InMemoryPeerNetwork()
        val localEndpoint = watchNetwork.endpoint("sonora-watch")
        val watchEndpoint = watchNetwork.endpoint("kardio")
        val localPlaylists = Page(listOf(PlaylistSummary("w1", "On the watch", 3)), 1, 0)
        val sonoraWatch = KoraNode("sonora-watch", peerDirectory = localEndpoint, transport = localEndpoint).apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { localPlaylists } }
        }
        val phoneRelay = WearRelay(phoneLink, backgroundScope)
        val watchRelay = WearRelay(watchLink, backgroundScope)
        val routes = listOf(PeerRoute(watchEndpoint, watchEndpoint), watchRelay.route)
        val watch = KoraNode(
            "kardio",
            peerDirectory = CompositePeerDirectory(routes.map { it.directory }, backgroundScope),
            transport = RoutingPeerTransport(routes),
        )
        sonoraEndpoint.connect(sonora)
        phoneEndpoint.connect(phone)
        localEndpoint.connect(sonoraWatch)
        watchEndpoint.connect(watch)
        phoneRelay.serve(phone, counterpart)
        watchRelay.serve(watch, counterpart)
        watchRelay.refresh()

        assertEquals(listOf("sonora-watch", "wear:kardio", "wear:sonora"), watch.peers.value.map { it.nodeId })
        assertEquals(localPlaylists, watch.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("sonora-watch")))
        assertEquals(playlists, watch.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("wear:sonora")))
        assertEquals(listOf("sonora-watch", "wear:sonora"), watch.providers(MediaLibrary))
    }

    @Test
    fun `the phone can reach peers of the watch the same way`() = runTest(UnconfinedTestDispatcher()) {
        val watchNetwork = InMemoryPeerNetwork()
        val localEndpoint = watchNetwork.endpoint("sonora-watch")
        val watchEndpoint = watchNetwork.endpoint("kardio")
        val watchOnlyPlaylists = Page(listOf(PlaylistSummary("w1", "On the watch", 3)), 1, 0)
        val sonoraWatch = KoraNode("sonora-watch", peerDirectory = localEndpoint, transport = localEndpoint).apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { watchOnlyPlaylists } }
        }
        val phoneRelay = WearRelay(phoneLink, backgroundScope)
        val watchRelay = WearRelay(watchLink, backgroundScope)
        val phoneRoutes = listOf(PeerRoute(phoneEndpoint, phoneEndpoint), phoneRelay.route)
        val phoneNode = KoraNode(
            "kardio",
            peerDirectory = CompositePeerDirectory(phoneRoutes.map { it.directory }, backgroundScope),
            transport = RoutingPeerTransport(phoneRoutes),
        )
        val watch = KoraNode("kardio", peerDirectory = watchEndpoint, transport = watchEndpoint)
        localEndpoint.connect(sonoraWatch)
        watchEndpoint.connect(watch)
        phoneEndpoint.connect(phoneNode)
        phoneRelay.serve(phoneNode, counterpart)
        watchRelay.serve(watch, counterpart)

        phoneRelay.refresh()

        assertEquals(listOf("wear:kardio", "wear:sonora-watch"), phoneNode.peers.value.map { it.nodeId }.filter { it.startsWith("wear:") })
        assertEquals(watchOnlyPlaylists, phoneNode.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("wear:sonora-watch")))
    }
}
