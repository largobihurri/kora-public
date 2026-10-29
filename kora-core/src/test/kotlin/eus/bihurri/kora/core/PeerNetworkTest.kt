package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.JsonCodec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.fitness.FitnessSession
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
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A future major version of media.library, as an app on a newer Kora would advertise it. */
private object MediaLibraryV2 : Capability(CapabilityId("media.library"), CapabilityVersion(2, 0))

@OptIn(ExperimentalCoroutinesApi::class)
class PeerNetworkTest {
    private val track = TrackSummary("t1", "Title", "Artist")
    private val sonoraPlaylists = Page(listOf(PlaylistSummary("p1", "Morning run", 12)), total = 1, offset = 0)
    private val nowPlaying = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 8)
    private var nowPlayingClosed = 0
    private val sonoraAudit = RecordingAudit()
    private val sonoraSeeks = mutableListOf<Pair<Long, Caller>>()

    private val network = InMemoryPeerNetwork()
    private val sonoraEndpoint = network.endpoint("sonora")
    private val kardioEndpoint = network.endpoint("kardio")

    private val sonora = KoraNode(
        nodeId = "sonora",
        displayName = "Sonora",
        audit = sonoraAudit,
        peerDirectory = sonoraEndpoint,
        transport = sonoraEndpoint,
    ).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { sonoraPlaylists }
        }
        provideStubbed(MediaPlayback) {
            on(MediaPlayback.SeekTo) { sonoraSeeks += input(it) to caller }
            on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.PAUSED, 5_000, 1, track) }
            emits(MediaPlayback.NowPlayingChanged) { nowPlaying.onCompletion { nowPlayingClosed++ } }
        }
    }

    private fun input(request: SeekRequest): Long = request.positionMs

    private val fix = LocationFix(43.26, -2.93, timeEpochMs = 99)

    private fun newKardio(callTimeout: Duration = 5.seconds) = KoraNode(
        nodeId = "kardio",
        displayName = "Kardio",
        peerDirectory = kardioEndpoint,
        transport = kardioEndpoint,
        callTimeout = callTimeout,
    ).apply {
        provideStubbed(LocationCapability) { on(LocationCapability.GetLastFix) { fix } }
    }

    private val kardio = newKardio()

    private fun connectBoth() {
        sonoraEndpoint.connect(sonora)
        kardioEndpoint.connect(kardio)
    }

    @Test
    fun `peers exposes the directory without the node itself`() = runTest {
        assertEquals(emptyList<PeerInfo>(), kardio.peers.value)

        connectBoth()

        assertEquals(listOf("sonora"), kardio.peers.value.map { it.nodeId })
        assertEquals(listOf("kardio"), sonora.peers.value.map { it.nodeId })
    }

    @Test
    fun `kardio lists sonora playlists and sonora sees a peer`() = runTest {
        connectBoth()

        val page = kardio.call(MediaLibrary.ListPlaylists, PageRequest())

        assertEquals(sonoraPlaylists, page)
        val entry = sonoraAudit.entries.single()
        assertEquals(Caller(CallerKind.PEER, "kardio"), entry.caller)
        assertEquals("list_playlists", entry.member)
    }

    @Test
    fun `typed call needs no extra arguments and the handler sees the peer`() = runTest {
        connectBoth()

        kardio.call(MediaPlayback.SeekTo, SeekRequest(42_000))

        assertEquals(listOf(42_000L to Caller(CallerKind.PEER, "kardio")), sonoraSeeks)
    }

    @Test
    fun `sonora can call kardio too`() = runTest {
        connectBoth()
        assertEquals(fix, sonora.call(LocationCapability.GetLastFix, NoInput))
    }

    @Test
    fun `kardio receives events emitted by sonora and cancelling closes the stream`() = runTest(UnconfinedTestDispatcher()) {
        connectBoth()
        val received = mutableListOf<NowPlaying>()
        val job = launch { kardio.events(MediaPlayback.NowPlayingChanged).collect { received += it } }

        nowPlaying.emit(NowPlaying(track, 1))
        nowPlaying.emit(NowPlaying(TrackSummary("t2", "Next"), 2))
        assertEquals(listOf(1L, 2L), received.map { it.startedAtEpochMs })
        assertEquals(AuditKind.SUBSCRIBE, sonoraAudit.entries.single().kind)
        assertEquals(Caller(CallerKind.PEER, "kardio"), sonoraAudit.entries.single().caller)

        job.cancel()
        job.join()
        assertEquals(1, nowPlayingClosed)
    }

    @Test
    fun `works over the json codec as well`() = runTest(UnconfinedTestDispatcher()) {
        val jsonNetwork = InMemoryPeerNetwork(JsonCodec)
        val a = jsonNetwork.endpoint("a")
        val b = jsonNetwork.endpoint("b")
        val flowing = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 1)
        val nodeA = KoraNode("a", codec = JsonCodec, peerDirectory = a, transport = a).apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { sonoraPlaylists } }
            provideStubbed(MediaPlayback) { emits(MediaPlayback.NowPlayingChanged) { flowing } }
        }
        val nodeB = KoraNode("b", codec = JsonCodec, peerDirectory = b, transport = b)
        a.connect(nodeA)
        b.connect(nodeB)

        assertEquals(sonoraPlaylists, nodeB.call(MediaLibrary.ListPlaylists, PageRequest()))
        val received = mutableListOf<NowPlaying>()
        val job = launch { nodeB.events(MediaPlayback.NowPlayingChanged).collect { received += it } }
        flowing.emit(NowPlaying(track, 7))
        job.cancel()
        assertEquals(listOf(NowPlaying(track, 7)), received)
    }

    // ---- Target selection -------------------------------------------------------------------------------------

    @Test
    fun `any prefers the local provider over a peer`() = runTest {
        val localLibrary = Page(listOf(PlaylistSummary("local", "Local", 1)), 1, 0)
        kardio.provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { localLibrary } }
        connectBoth()

        assertEquals(localLibrary, kardio.call(MediaLibrary.ListPlaylists, PageRequest()))
        assertEquals(sonoraPlaylists, kardio.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("sonora")))
        assertEquals(localLibrary, kardio.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Local))
    }

    @Test
    fun `local target without a local provider is unavailable`() = runTest {
        connectBoth()
        assertUnavailable { kardio.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Local) }
    }

    @Test
    fun `peer target that is unknown or does not offer the capability is unavailable`() = runTest {
        connectBoth()
        assertUnavailable { kardio.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("ghost")) }
        assertUnavailable { sonora.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("kardio")) }
    }

    @Test
    fun `nobody offering the capability is provider_unavailable`() = runTest {
        connectBoth()
        assertUnavailable { kardio.call(FitnessSession.GetActiveSession, NoInput) }
    }

    @Test
    fun `standalone node serves local calls with no peers`() = runTest {
        val alone = KoraNode("alone").apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.IDLE) } }
        }

        assertEquals(PlaybackState(PlaybackStatus.IDLE), alone.call(MediaPlayback.GetState, NoInput))
        assertUnavailable { alone.call(MediaLibrary.ListPlaylists, PageRequest()) }
        assertEquals(listOf("alone"), alone.providers(MediaPlayback))
        assertTrue(alone.isAvailable(MediaPlayback))
        assertFalse(alone.isAvailable(MediaLibrary))
    }

    @Test
    fun `local calls use the SELF caller through the same pipeline`() = runTest {
        val audit = RecordingAudit()
        val node = KoraNode("alone", audit = audit, clock = FakeClock(77)).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.IDLE) } }
        }
        node.call(MediaPlayback.GetState, NoInput)

        val entry = audit.entries.single()
        assertEquals(Caller(CallerKind.SELF, "alone"), entry.caller)
        assertEquals(AuditOutcome.Ok, entry.outcome)
    }

    @Test
    fun `local call respects a restrictive policy exactly like an inbound one`() = runTest {
        val node = KoraNode("alone", policy = { caller, _ -> caller.kind != CallerKind.SELF }).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.IDLE) } }
        }
        assertFailsWith(ErrorCode.UNAUTHORIZED) { node.call(MediaPlayback.GetState, NoInput) }
    }

    // ---- Versions ---------------------------------------------------------------------------------------------

    @Test
    fun `a peer advertising only an incompatible major is incompatible_version`() = runTest {
        val future = network.endpoint("future")
        val futureNode = KoraNode("future", peerDirectory = future, transport = future).apply {
            provideStubbed(MediaLibraryV2) { }
        }
        future.connect(futureNode)
        kardioEndpoint.connect(kardio)

        assertFailsWith(ErrorCode.INCOMPATIBLE_VERSION) { kardio.call(MediaLibrary.ListPlaylists, PageRequest()) }
        assertFalse(kardio.isAvailable(MediaLibrary))
        assertEquals(emptyList<String>(), kardio.providers(MediaLibrary))
    }

    @Test
    fun `any skips incompatible peers and picks the first compatible one`() = runTest {
        val future = network.endpoint("future")
        future.connect(KoraNode("future", peerDirectory = future, transport = future).apply { provideStubbed(MediaLibraryV2) { } })
        connectBoth()

        assertEquals(sonoraPlaylists, kardio.call(MediaLibrary.ListPlaylists, PageRequest()))
        assertEquals(listOf("sonora"), kardio.providers(MediaLibrary))
        assertFailsWith(ErrorCode.INCOMPATIBLE_VERSION) { kardio.call(MediaLibrary.ListPlaylists, PageRequest(), Target.Peer("future")) }
    }

    // ---- Availability -----------------------------------------------------------------------------------------

    @Test
    fun `availability flips when a peer joins and leaves`() = runTest(UnconfinedTestDispatcher()) {
        kardioEndpoint.connect(kardio)
        val seen = mutableListOf<Boolean>()
        val job = launch { kardio.availability(MediaPlayback).collect { seen += it } }
        assertEquals(listOf(false), seen)

        sonoraEndpoint.connect(sonora)
        assertEquals(listOf(false, true), seen)
        assertEquals(listOf("sonora"), kardio.providers(MediaPlayback))

        sonoraEndpoint.disconnect()
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }

    @Test
    fun `availability reacts to local registration and ignores unrelated changes`() = runTest(UnconfinedTestDispatcher()) {
        val node = KoraNode("alone")
        val seen = mutableListOf<Boolean>()
        val job = launch { node.availability(MediaPlayback).collect { seen += it } }

        node.provideStubbed(MediaLibrary) { }
        node.provideStubbed(MediaPlayback) { }

        assertEquals(listOf(false, true), seen)
        job.cancel()
    }

    @Test
    fun `descriptors are snapshots until refresh`() = runTest {
        kardioEndpoint.connect(kardio)
        val lateSonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint)
        sonoraEndpoint.connect(lateSonora)
        assertFalse(kardio.isAvailable(MediaLibrary))

        lateSonora.provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { sonoraPlaylists } }
        assertFalse(kardio.isAvailable(MediaLibrary))
        network.refresh()

        assertTrue(kardio.isAvailable(MediaLibrary))
        assertEquals(sonoraPlaylists, kardio.call(MediaLibrary.ListPlaylists, PageRequest()))
    }

    // ---- Timeout, transport failures, ids ----------------------------------------------------------------------

    @Test
    fun `slow peer times out on virtual time`() = runTest {
        sonora.provideStubbed(FitnessSession) { on(FitnessSession.GetActiveSession) { delay(60.seconds); null } }
        val impatient = newKardio(callTimeout = 2.seconds)
        sonoraEndpoint.connect(sonora)
        kardioEndpoint.connect(impatient)

        val outcome = async { runCatching { impatient.call(FitnessSession.GetActiveSession, NoInput) } }
        advanceTimeBy(1_999)
        assertFalse(outcome.isCompleted)
        advanceTimeBy(2)

        val error = outcome.await().exceptionOrNull() as KoraException
        assertEquals(ErrorCode.TIMEOUT, error.error.code)
    }

    @Test
    fun `fast peer answers within the timeout`() = runTest {
        sonora.provideStubbed(FitnessSession) { on(FitnessSession.GetActiveSession) { delay(1.seconds); null } }
        connectBoth()
        val outcome = async { kardio.call(FitnessSession.GetActiveSession, NoInput) }
        advanceTimeBy(1_001)
        assertEquals(null, outcome.await())
    }

    @Test
    fun `transport exceptions become provider_unavailable and ids are unique`() = runTest {
        val ids = mutableListOf<String>()
        val flaky = object : PeerTransport {
            override suspend fun call(peer: PeerInfo, request: KoraRequest): KoraResponse {
                ids += request.id
                throw IOException("binder died")
            }

            override fun subscribe(peer: PeerInfo, request: SubscribeRequest) =
                throw UnsupportedOperationException()
        }
        val directory = object : PeerDirectory {
            override val peers = MutableStateFlow(listOf(PeerInfo(sonora.describe())))
        }
        val node = KoraNode("kardio", peerDirectory = directory, transport = flaky)

        repeat(2) {
            assertFailsWith(ErrorCode.PROVIDER_UNAVAILABLE) { node.call(MediaLibrary.ListPlaylists, PageRequest()) }
        }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("kardio-") })
    }

    @Test
    fun `calling a peer that already left is unavailable at the transport`() = runTest {
        connectBoth()
        val stale = kardioEndpoint.peers.value.single()
        sonoraEndpoint.disconnect()
        assertFailsWith(ErrorCode.PROVIDER_UNAVAILABLE) { kardioEndpoint.call(stale, request(MediaLibrary.ListPlaylists, PageRequest())) }
    }

    private suspend fun assertUnavailable(block: suspend () -> Unit) = assertFailsWith(ErrorCode.PROVIDER_UNAVAILABLE, block)

    private suspend fun assertFailsWith(code: ErrorCode, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: KoraException) {
            e
        }
        assertEquals(code, error?.error?.code, "expected a KoraException with $code")
    }
}
