package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.testing.InMemoryPeerNetwork
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Deadlines: a call forwarded with [KoraNode.dispatch] ends at the node's call timeout, and a handler whose own
 * `withTimeout` expires answers `timeout` instead of ending without a response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDeadlineTest {
    private val playlists = Page(listOf(PlaylistSummary("p1", "Morning run", 12)), total = 1, offset = 0)
    private val access = FakeExternalAccess(enabled = true).apply { grants[AGENT] = setOf(MediaLibrary.ReadScope) }
    private val audit = RecordingAudit()

    private val network = InMemoryPeerNetwork()
    private val sonoraEndpoint = network.endpoint("sonora")
    private val bridgeEndpoint = network.endpoint("bridge")

    private val listRequest get() = request(MediaLibrary.ListPlaylists, PageRequest())
    private val pauseRequest get() = request(MediaPlayback.Pause, NoInput)

    /**
     * A bridge whose call timeout is two seconds, audited by [audit], with a peer that answers `list_playlists`
     * after [answersAfter]; [onServing] runs when the request reaches the peer's handler.
     */
    private fun bridgeTo(answersAfter: Duration, onServing: () -> Unit = {}): KoraNode {
        val sonora = KoraNode("sonora", peerDirectory = sonoraEndpoint, transport = sonoraEndpoint).apply {
            provideStubbed(MediaLibrary) {
                on(MediaLibrary.ListPlaylists) {
                    onServing()
                    delay(answersAfter)
                    playlists
                }
            }
        }
        val bridge = KoraNode(
            nodeId = "bridge",
            policy = DefaultCallerPolicy(access),
            audit = audit,
            peerDirectory = bridgeEndpoint,
            transport = bridgeEndpoint,
            clock = FakeClock(),
            callTimeout = 2.seconds,
        )
        sonoraEndpoint.connect(sonora)
        bridgeEndpoint.connect(bridge)
        return bridge
    }

    // ---- Forwarded calls ---------------------------------------------------------------------------------------

    @Test
    fun `a forwarded call to a provider that does not answer ends with timeout at the deadline`() = runTest {
        val bridge = bridgeTo(answersAfter = 60.seconds)

        val outcome = async { bridge.dispatch(AGENT, listRequest) }
        advanceTimeBy(1_999)
        assertFalse(outcome.isCompleted, "the deadline has not passed yet")
        advanceTimeBy(2)

        assertTrue(outcome.isCompleted, "the answer arrives at the deadline, not when the provider finally answers")
        assertFailure(ErrorCode.TIMEOUT, outcome.await())
        val entry = audit.entries.single()
        assertEquals(AGENT, entry.caller)
        assertEquals(MediaLibrary.ListPlaylists.name, entry.member)
        assertEquals(AuditKind.CALL, entry.kind)
        assertEquals(AuditOutcome.Failed(ErrorCode.TIMEOUT), entry.outcome)
    }

    @Test
    fun `a forwarded call that answers before the deadline is returned`() = runTest {
        val bridge = bridgeTo(answersAfter = 1_900.milliseconds)

        val response = bridge.dispatch(AGENT, listRequest)

        assertEquals(playlists, response.output(MediaLibrary.ListPlaylists))
        assertEquals(1_900, currentTime)
        assertEquals(AuditOutcome.Ok, audit.entries.single().outcome)
    }

    @Test
    fun `cancelling the caller of a forwarded call propagates and is not audited`() = runTest {
        val serving = CompletableDeferred<Unit>()
        val bridge = bridgeTo(answersAfter = 60.seconds, onServing = { serving.complete(Unit) })
        var response: KoraResponse? = null

        val job = launch { response = bridge.dispatch(AGENT, listRequest) }
        serving.await()
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(null, response)
        assertTrue(audit.entries.isEmpty(), "a cancelled call is not audited")
        assertEquals(0, currentTime, "the call ended when it was cancelled, before the deadline")
    }

    // ---- Timeouts inside a handler -----------------------------------------------------------------------------

    @Test
    fun `a handler whose own timeout expires answers timeout and is audited`() = runTest {
        var runs = 0
        val node = KoraNode("sonora", audit = audit, clock = FakeClock()).apply {
            provideStubbed(MediaPlayback) {
                on(MediaPlayback.Pause) {
                    runs++
                    withTimeout(1.seconds) { delay(60.seconds) }
                }
            }
        }

        val response = runCatching { node.handle(PEER, pauseRequest) }
            .getOrElse { fail("handle must answer, not throw", it) }

        assertFailure(ErrorCode.TIMEOUT, response)
        assertEquals(pauseRequest.id, response.id)
        assertEquals(1_000, currentTime, "answered when the handler's timeout expired")
        assertEquals(1, runs)
        val entry = audit.entries.single()
        assertEquals(PEER, entry.caller)
        assertEquals(MediaPlayback.Pause.name, entry.member)
        assertEquals(AuditKind.CALL, entry.kind)
        assertEquals(AuditOutcome.Failed(ErrorCode.TIMEOUT), entry.outcome)
    }

    @Test
    fun `a timeout of the scope around a handler stays a cancellation and is not audited`() = runTest {
        val node = KoraNode("sonora", audit = audit, clock = FakeClock()).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Pause) { delay(60.seconds) } }
        }

        val outcome = runCatching { withTimeout(1.seconds) { node.handle(PEER, pauseRequest) } }

        assertInstanceOf(TimeoutCancellationException::class.java, outcome.exceptionOrNull())
        assertTrue(audit.entries.isEmpty(), "a cancelled call is not audited")
    }
}
