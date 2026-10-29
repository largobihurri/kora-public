package eus.bihurri.kora.core

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [KoraNode.dispatchSubscribe] and the `target` of [KoraNode.dispatch]. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDispatchSubscribeTest {
    private val nowPlaying = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 8)
    private val access = FakeExternalAccess(enabled = true)
    private val audit = RecordingAudit()

    private val network = InMemoryPeerNetwork()
    private val sonoraEndpoint = network.endpoint("sonora")
    private val bridgeEndpoint = network.endpoint("bridge")

    private val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
        provideStubbed(MediaPlayback) { emits(MediaPlayback.NowPlayingChanged) { nowPlaying } }
    }
    private val bridge = KoraNode(
        "bridge",
        policy = DefaultCallerPolicy(access),
        audit = audit,
        peerDirectory = bridgeEndpoint,
        transport = bridgeEndpoint,
    )

    private val subscribeRequest = subscription(MediaPlayback.NowPlayingChanged)
    private val track = TrackSummary("t1", "Title")

    private fun decode(event: eus.bihurri.kora.contract.KoraEvent): NowPlaying =
        MediaPlayback.NowPlayingChanged.decode(eus.bihurri.kora.contract.CborCodec, event.payload)

    @Test
    fun `an authorized agent receives the events of a peer and the peer sees the bridge`() = runTest(UnconfinedTestDispatcher()) {
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)
        val received = mutableListOf<NowPlaying>()

        val job = launch { bridge.dispatchSubscribe(AGENT, subscribeRequest).collect { received += decode(it) } }
        nowPlaying.emit(NowPlaying(track, 1))
        job.cancel()

        assertEquals(listOf(1L), received.map { it.startedAtEpochMs })
        assertEquals(AuditKind.SUBSCRIBE, audit.entries.single().kind)
        assertEquals(AGENT, audit.entries.single().caller)
    }

    @Test
    fun `an agent without the read scope is refused before the peer is asked`() = runTest {
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(MediaPlayback.ControlScope)

        val failure = runCatching { bridge.dispatchSubscribe(AGENT, subscribeRequest).collect {} }.exceptionOrNull()

        assertEquals(ErrorCode.UNAUTHORIZED, (failure as KoraException).error.code)
        assertEquals(AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), audit.entries.single().outcome)
    }

    @Test
    fun `nobody offering the event is unavailable and audited`() = runTest {
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)

        val failure = runCatching { bridge.dispatchSubscribe(AGENT, subscribeRequest).collect {} }.exceptionOrNull()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
        assertEquals(AuditOutcome.Failed(ErrorCode.PROVIDER_UNAVAILABLE), audit.entries.single().outcome)
    }

    @Test
    fun `an unknown event of a peer capability is refused`() = runTest {
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
        val unknown = subscribeRequest.let { eus.bihurri.kora.contract.SubscribeRequest(it.subscriptionId, it.capability, it.version, "nope") }

        val failure = runCatching { bridge.dispatchSubscribe(AGENT, unknown).collect {} }.exceptionOrNull()

        assertEquals(ErrorCode.UNKNOWN_OPERATION, (failure as KoraException).error.code)
    }

    @Test
    fun `a local provider is served by subscribe`() = runTest(UnconfinedTestDispatcher()) {
        bridge.provideStubbed(MediaPlayback) { emits(MediaPlayback.NowPlayingChanged) { nowPlaying } }
        bridgeEndpoint.connect(bridge)
        val received = mutableListOf<NowPlaying>()
        val job = launch { bridge.dispatchSubscribe(SELF, subscribeRequest).collect { received += decode(it) } }

        nowPlaying.emit(NowPlaying(track, 5))
        job.cancel()

        assertEquals(listOf(5L), received.map { it.startedAtEpochMs })
    }

    @Test
    fun `an explicit peer target reaches that peer, an unknown one is unavailable`() = runTest {
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)
        val state = request(MediaPlayback.GetState, eus.bihurri.kora.contract.NoInput)

        assertEquals(ErrorCode.UNKNOWN_OPERATION, bridge.dispatch(AGENT, state, Target.Peer("sonora")).errorCode())
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, bridge.dispatch(AGENT, state, Target.Peer("ghost")).errorCode())
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, bridge.dispatch(AGENT, state, Target.Local).errorCode())
    }
}
