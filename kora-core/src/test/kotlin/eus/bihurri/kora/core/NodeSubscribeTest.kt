package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraEvent
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.SubscribeRequest
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.NowPlaying
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeSubscribeTest {
    private val audit = RecordingAudit()
    private val access = FakeExternalAccess()
    private val nowPlaying = MutableSharedFlow<NowPlaying>(extraBufferCapacity = 8)
    private var opened = 0
    private var closed = 0
    private val track = TrackSummary("t1", "Title")

    private val node = KoraNode("sonora", policy = DefaultCallerPolicy(access), audit = audit, clock = FakeClock()).apply {
        provideStubbed(MediaPlayback) {
            emits(MediaPlayback.NowPlayingChanged) {
                opened++
                nowPlaying.onCompletion { closed++ }
            }
        }
    }

    private fun failureOf(caller: Caller, request: SubscribeRequest): ErrorCode =
        assertThrows(KoraException::class.java) { runBlockingCollect(caller, request) }.error.code

    private fun runBlockingCollect(caller: Caller, request: SubscribeRequest) =
        runBlocking { node.subscribe(caller, request).first() }

    @Test
    fun `delivers encoded events tagged with the subscription id`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<KoraEvent>()
        val job = launch { node.subscribe(PEER, subscription(MediaPlayback.NowPlayingChanged, "sub-42")).toList(received) }

        nowPlaying.emit(NowPlaying(track, 111))
        job.cancel()

        val event = received.single()
        assertEquals("sub-42", event.subscriptionId)
        assertEquals("media.playback", event.capability)
        assertEquals("now_playing_changed", event.event)
        assertEquals(NowPlaying(track, 111), MediaPlayback.NowPlayingChanged.decode(CborCodec, event.payload))
    }

    @Test
    fun `cancelling the collector stops the provider flow`() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { node.subscribe(SELF, subscription(MediaPlayback.NowPlayingChanged)).collect { } }
        assertEquals(1, opened)
        assertEquals(0, closed)

        job.cancel()
        job.join()

        assertEquals(1, closed)
        assertEquals(0, nowPlaying.subscriptionCount.value)
    }

    @Test
    fun `policy is checked before the provider flow starts`() {
        assertEquals(ErrorCode.UNAUTHORIZED, failureOf(EXTERNAL, subscription(MediaPlayback.NowPlayingChanged)))
        assertEquals(0, opened)
        assertEquals(
            listOf(AuditEntry(audit.entries.single().timeEpochMs, EXTERNAL, "media.playback", "now_playing_changed", AuditKind.SUBSCRIBE, AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), 0)),
            audit.entries,
        )
    }

    @Test
    fun `granted external caller can subscribe`() = runTest(UnconfinedTestDispatcher()) {
        access.enabled = true
        access.grants[EXTERNAL] = setOf(MediaPlayback.ReadScope)
        val job = launch { node.subscribe(EXTERNAL, subscription(MediaPlayback.NowPlayingChanged)).collect { } }
        assertEquals(1, opened)
        job.cancel()
    }

    @Test
    fun `refusals map to their error codes`() {
        val unknownCapability = SubscribeRequest("s", "location", CapabilityVersion(1, 0), "fix_changed")
        val incompatible = subscription(MediaPlayback.NowPlayingChanged, version = CapabilityVersion(2, 0))
        val unknownEvent = SubscribeRequest("s", "media.playback", CapabilityVersion(1, 0), "teleported")
        val operationName = SubscribeRequest("s", "media.playback", CapabilityVersion(1, 0), "pause")

        assertEquals(ErrorCode.UNKNOWN_CAPABILITY, failureOf(SELF, unknownCapability))
        assertEquals(ErrorCode.INCOMPATIBLE_VERSION, failureOf(SELF, incompatible))
        assertEquals(ErrorCode.UNKNOWN_OPERATION, failureOf(SELF, unknownEvent))
        assertEquals(ErrorCode.UNKNOWN_OPERATION, failureOf(SELF, operationName))
        assertEquals(0, opened)
        assertTrue(audit.entries.all { it.kind == AuditKind.SUBSCRIBE && it.outcome is AuditOutcome.Failed })
    }

    @Test
    fun `successful subscription is audited once when established`() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { node.subscribe(PEER, subscription(MediaPlayback.NowPlayingChanged)).collect { } }
        nowPlaying.emit(NowPlaying(track, 1))
        nowPlaying.emit(NowPlaying(track, 2))
        job.cancel()

        val entry = audit.entries.single()
        assertEquals(AuditKind.SUBSCRIBE, entry.kind)
        assertEquals(AuditOutcome.Ok, entry.outcome)
        assertEquals(PEER, entry.caller)
    }

    @Test
    fun `provider flow failure becomes an internal error without details`() {
        val errors = mutableListOf<Throwable>()
        val broken = KoraNode("n", onInternalError = { errors += it }).apply {
            provideStubbed(MediaPlayback) {
                emits(MediaPlayback.NowPlayingChanged) { flow { throw IllegalStateException("secret path /data/x") } }
            }
        }
        val thrown = assertThrows(KoraException::class.java) {
            runBlocking { broken.subscribe(SELF, subscription(MediaPlayback.NowPlayingChanged)).toList() }
        }

        assertEquals(ErrorCode.INTERNAL, thrown.error.code)
        assertFalse(thrown.error.message.contains("secret"))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a KoraException raised by the provider flow passes through`() {
        val failing = KoraNode("n").apply {
            provideStubbed(MediaPlayback) {
                emits(MediaPlayback.NowPlayingChanged) { flow { throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "no player")) } }
            }
        }
        val thrown = assertThrows(KoraException::class.java) {
            runBlocking { failing.subscribe(SELF, subscription(MediaPlayback.NowPlayingChanged)).toList() }
        }
        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "no player"), thrown.error)
    }
}
