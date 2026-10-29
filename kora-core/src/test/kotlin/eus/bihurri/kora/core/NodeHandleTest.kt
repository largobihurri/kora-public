package eus.bihurri.kora.core

import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.fitness.FitnessSession
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeHandleTest {
    private val audit = RecordingAudit()
    private val access = FakeExternalAccess()
    private val clock = FakeClock(now = 5_000)
    private val internalErrors = mutableListOf<Throwable>()
    private val seekedTo = mutableListOf<Long>()
    private val seenCallers = mutableListOf<Caller>()

    private val node = KoraNode(
        nodeId = "sonora",
        policy = DefaultCallerPolicy(access),
        audit = audit,
        clock = clock,
        onInternalError = { internalErrors += it },
    ).apply {
        provideStubbed(MediaPlayback) {
            on(MediaPlayback.SeekTo) { input ->
                seenCallers += caller
                seekedTo += input.positionMs
            }
        }
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page -> Page(listOf(PlaylistSummary("p1", "Mix", 3)), total = 1, offset = page.offset) }
            on(MediaLibrary.SearchTracks) { throw IllegalStateException("db at /data/user/0/secret.db is locked") }
        }
    }

    @Test
    fun `serves a request and exposes the caller to the handler`() = runTest {
        val response = node.handle(PEER, request(MediaPlayback.SeekTo, SeekRequest(1_500)))

        assertEquals(Unit, response.output(MediaPlayback.SeekTo))
        assertEquals("r1", response.id)
        assertEquals(listOf(1_500L), seekedTo)
        assertEquals(listOf(PEER), seenCallers)
    }

    @Test
    fun `result payload is encoded with the node codec`() = runTest {
        val response = node.handle(SELF, request(MediaLibrary.ListPlaylists, PageRequest(10, 20)))
        assertEquals(20, response.output(MediaLibrary.ListPlaylists).offset)
    }

    @Test
    fun `unknown capability`() = runTest {
        val response = node.handle(SELF, request(FitnessSession.GetActiveSession, NoInput))
        assertFailure(ErrorCode.UNKNOWN_CAPABILITY, response)
    }

    @Test
    fun `unknown operation name`() = runTest {
        val raw = KoraRequest("r9", "media.playback", CapabilityVersion(1, 0), "teleport", ByteArray(0))
        val response = node.handle(SELF, raw)
        assertFailure(ErrorCode.UNKNOWN_OPERATION, response)
        assertEquals("r9", response.id)
    }

    @Test
    fun `declared operation without a handler is unknown`() = runTest {
        assertFailure(ErrorCode.UNKNOWN_OPERATION, node.handle(SELF, request(MediaPlayback.Pause, NoInput)))
    }

    @Test
    fun `incompatible versions are refused`() = runTest {
        val newerMajor = request(MediaPlayback.SeekTo, SeekRequest(1), version = CapabilityVersion(2, 0))
        val newerMinor = request(MediaPlayback.SeekTo, SeekRequest(1), version = CapabilityVersion(1, 1))
        val olderMajor = request(MediaPlayback.SeekTo, SeekRequest(1), version = CapabilityVersion(0, 9))
        for (bad in listOf(newerMajor, newerMinor, olderMajor)) {
            assertFailure(ErrorCode.INCOMPATIBLE_VERSION, node.handle(SELF, bad))
        }
        assertTrue(seekedTo.isEmpty())
    }

    @Test
    fun `policy denial happens before the handler runs`() = runTest {
        val response = node.handle(EXTERNAL, request(MediaPlayback.SeekTo, SeekRequest(1)))

        assertFailure(ErrorCode.UNAUTHORIZED, response)
        assertTrue(seekedTo.isEmpty())
    }

    @Test
    fun `external caller with master switch and grant is served`() = runTest {
        access.enabled = true
        access.grants[EXTERNAL] = setOf(MediaPlayback.ControlScope)

        assertEquals(Unit, node.handle(EXTERNAL, request(MediaPlayback.SeekTo, SeekRequest(7))).output(MediaPlayback.SeekTo))
        assertEquals(listOf(7L), seekedTo)
    }

    @Test
    fun `invalid payload never reaches the handler`() = runTest {
        val garbage = KoraRequest("r1", "media.playback", CapabilityVersion(1, 0), "seek_to", byteArrayOf(0xFF.toByte(), 0x00))
        val response = node.handle(SELF, garbage)

        assertFailure(ErrorCode.INVALID_PAYLOAD, response)
        assertTrue(seekedTo.isEmpty())
        assertTrue(internalErrors.isEmpty(), "a bad payload is the caller's fault, not an internal error")
    }

    @Test
    fun `payload for another operation is invalid`() = runTest {
        val wrong = KoraRequest("r1", "media.playback", CapabilityVersion(1, 0), "seek_to", CborCodec.encode(SearchRequest.serializer(), SearchRequest("x")))
        assertFailure(ErrorCode.INVALID_PAYLOAD, node.handle(SELF, wrong))
    }

    @Test
    fun `a KoraException from the handler passes through unchanged`() = runTest {
        val failing = KoraNode("n").apply {
            provideStubbed(MediaPlayback) {
                on(MediaPlayback.Pause) { throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "player not ready")) }
            }
        }
        val response = failing.handle(SELF, request(MediaPlayback.Pause, NoInput))

        val failure = response as KoraResponse.Failure
        assertEquals(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "player not ready"), failure.error)
    }

    @Test
    fun `any other exception becomes internal without leaking details`() = runTest {
        val response = node.handle(SELF, request(MediaLibrary.SearchTracks, SearchRequest("x")))

        val failure = response as KoraResponse.Failure
        assertEquals(ErrorCode.INTERNAL, failure.error.code)
        assertFalse(failure.error.message.contains("secret"), failure.error.message)
        assertFalse(failure.error.message.contains("IllegalState"), failure.error.message)
        assertEquals(1, internalErrors.size, "the original exception is reported to the app")
        assertTrue(internalErrors.single().message!!.contains("secret.db"))
    }

    @Test
    fun `an illegal argument inside the handler is internal, not invalid payload`() = runTest {
        val buggy = KoraNode("n").apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Pause) { require(false) { "bug" } } }
        }
        assertFailure(ErrorCode.INTERNAL, buggy.handle(SELF, request(MediaPlayback.Pause, NoInput)))
    }

    @Test
    fun `a throwing policy fails closed as internal`() = runTest {
        val broken = KoraNode("n", policy = { _, _ -> error("settings unreadable") }).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Pause) { } }
        }
        assertFailure(ErrorCode.INTERNAL, broken.handle(SELF, request(MediaPlayback.Pause, NoInput)))
    }

    @Test
    fun `cancellation propagates and is not turned into a response`() = runTest {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val slow = KoraNode("n", audit = audit).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Pause) { started.complete(Unit); never.await() } }
        }
        var response: KoraResponse? = null
        val job = launch { response = slow.handle(SELF, request(MediaPlayback.Pause, NoInput)) }
        started.await()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(null, response)
        assertTrue(audit.entries.isEmpty(), "a cancelled call is not audited")
        assertTrue(internalErrors.isEmpty())
    }

    @Test
    fun `audit records success and failures with caller, member, outcome and duration`() = runTest {
        val slowClock = FakeClock(now = 10_000)
        val timed = KoraNode("n", policy = DefaultCallerPolicy(access), audit = audit, clock = slowClock).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.SeekTo) { slowClock.now += 40 } }
        }
        timed.handle(PEER, request(MediaPlayback.SeekTo, SeekRequest(1)))
        timed.handle(EXTERNAL, request(MediaPlayback.SeekTo, SeekRequest(1)))
        timed.handle(SELF, request(MediaPlayback.Pause, NoInput))

        val (ok, denied, unknown) = audit.entries
        assertEquals(AuditEntry(10_040, PEER, "media.playback", "seek_to", AuditKind.CALL, AuditOutcome.Ok, 40), ok)
        assertEquals(AuditOutcome.Failed(ErrorCode.UNAUTHORIZED), denied.outcome)
        assertEquals(EXTERNAL, denied.caller)
        assertEquals(AuditOutcome.Failed(ErrorCode.UNKNOWN_OPERATION), unknown.outcome)
        assertEquals("pause", unknown.member)
    }

    @Test
    fun `audit covers unknown capabilities and internal errors`() = runTest {
        node.handle(SELF, request(FitnessSession.GetActiveSession, NoInput))
        node.handle(SELF, request(MediaLibrary.SearchTracks, SearchRequest("x")))

        assertEquals(
            listOf(AuditOutcome.Failed(ErrorCode.UNKNOWN_CAPABILITY), AuditOutcome.Failed(ErrorCode.INTERNAL)),
            audit.entries.map { it.outcome },
        )
        assertEquals("fitness.session", audit.entries.first().capability)
    }

    @Test
    fun `audit entries never contain payloads`() = runTest {
        val marker = "TOP-SECRET-QUERY-8841"
        node.handle(SELF, request(MediaLibrary.SearchTracks, SearchRequest(marker)))
        node.handle(SELF, request(MediaPlayback.SeekTo, SeekRequest(123_456_789)))

        assertEquals(2, audit.entries.size)
        for (entry in audit.entries) {
            assertFalse(entry.toString().contains(marker))
            assertFalse(entry.toString().contains("123456789"))
        }
        val fields = AuditEntry::class.java.declaredFields.map { it.type }
        assertTrue(fields.none { it.isArray }, "AuditEntry must not hold byte arrays: $fields")
    }

    @Test
    fun `provide rejects a second registration and foreign specs`() {
        assertThrows(IllegalStateException::class.java) { node.provideStubbed(MediaPlayback) { } }
        assertThrows(IllegalArgumentException::class.java) {
            KoraNode("x").provideStubbed(MediaPlayback) { on(MediaLibrary.ListPlaylists) { Page(emptyList(), 0, 0) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KoraNode("x").provideStubbed(MediaPlayback) {
                on(MediaPlayback.Pause) { }
                on(MediaPlayback.Pause) { }
            }
        }
    }

    @Test
    fun `describe lists registered capabilities`() {
        val descriptor = node.describe()
        assertEquals("sonora", descriptor.nodeId)
        assertEquals(listOf("media.library", "media.playback"), descriptor.capabilities.map { it.id })
        assertEquals(emptyList<Any>(), KoraNode("empty").describe().capabilities)
    }

    @Test
    fun `describe reports the Kora library version for diagnostics`() {
        assertEquals(eus.bihurri.kora.contract.KoraBuild.VERSION, node.describe().koraVersion)
    }

    @Test
    fun `scope of a handler comes from its spec`() = runTest {
        access.enabled = true
        access.grants[AGENT] = setOf(Scope("media.playback.read")) // read does not allow seek_to (control)
        assertFailure(ErrorCode.UNAUTHORIZED, node.handle(AGENT, request(MediaPlayback.SeekTo, SeekRequest(1))))
        assertEquals(MediaPlayback.ControlScope, MediaPlayback.SeekTo.scope)
    }
}
