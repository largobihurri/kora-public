package eus.bihurri.kora.tailnet

import eus.bihurri.kora.mcp.McpServerInfo
import eus.bihurri.kora.mcp.http.DeniedAttempt
import eus.bihurri.kora.mcp.http.DenialReason
import eus.bihurri.kora.mcp.http.InMemoryPairingStore
import eus.bihurri.kora.mcp.http.McpHttpConfig
import eus.bihurri.kora.mcp.http.McpHttpServer
import eus.bihurri.kora.mcp.http.Pairing
import eus.bihurri.kora.mcp.http.PairingTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The listener's life against real sockets on loopback (the test-only `allowNonTailnetAddress` escape hatch) with a
 * fake address source and virtual time for the idle timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailnetControllerTest {
    private val a = address("127.0.0.1")
    private val b = address("127.0.0.2")

    /**
     * [slept] is time the device spent in deep sleep: [clock] counts it, the test scheduler (like a coroutine delay on a
     * phone) does not.
     */
    private class Rig(scope: CoroutineScope, idle: Duration = 30.minutes, virtualNow: () -> Long = { 0L }) : AutoCloseable {
        val source = FakeAddressSource()
        val alarm = FakeAlarm()
        val store = InMemoryPairingStore()
        val port = freePort()
        val logs = mutableListOf<String>()

        @Volatile
        var slept = 0L
        val controller = TailnetController(
            addresses = source,
            port = port,
            path = "/mcp",
            idleTimeout = idle,
            scope = scope,
            clock = { virtualNow() * 1_000_000L + slept },
            alarm = alarm,
            pairings = store,
            log = { logs += it },
        ) { guarded, listener ->
            McpHttpServer(
                node = testNode(),
                serverInfo = McpServerInfo("kardio", "1.0"),
                pairings = guarded,
                config = McpHttpConfig(allowNonTailnetAddress = true, deniedThrottle = Duration.ZERO),
                listener = listener,
            )
        }

        fun sleep(duration: Duration) {
            slept += duration.inWholeNanoseconds
        }

        fun pair(): String {
            val issued = PairingTokens.generate()
            store.add(Pairing(PairingTokens.generateId(), "agent"), issued.hash)
            return issued.token
        }

        override fun close() = controller.stop()
    }

    private fun TestScope.rig(idle: Duration = 30.minutes) = Rig(backgroundScope, idle) { currentTime }

    private fun accepts(address: InetAddress, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), 1_000) }
        true
    } catch (_: ConnectException) {
        false
    }

    @Test
    fun `the status follows the address, waiting then listening then waiting again then listening elsewhere`() = runTest {
        rig().use { rig ->
            assertEquals(TailnetStatus.Off, rig.controller.status.value)

            rig.controller.start()
            runCurrent()
            assertEquals(TailnetStatus.WaitingForTailnet, rig.controller.status.value)
            assertTrue(rig.controller.running.value)

            rig.source.emit(a)
            runCurrent()
            assertEquals(TailnetStatus.Listening("127.0.0.1", rig.port, "http://127.0.0.1:${rig.port}/mcp"), rig.controller.status.value)

            rig.source.emit(null)
            runCurrent()
            assertEquals(TailnetStatus.WaitingForTailnet, rig.controller.status.value)
            assertFalse(accepts(a, rig.port), "nothing listens while the tailnet is down")

            rig.source.emit(b)
            runCurrent()
            assertEquals(TailnetStatus.Listening("127.0.0.2", rig.port, "http://127.0.0.2:${rig.port}/mcp"), rig.controller.status.value)
        }
    }

    @Test
    fun `an address that is there at start is used at once`() = runTest {
        rig().use { rig ->
            rig.source.initial = a

            rig.controller.start()
            runCurrent()

            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)
            assertNotEquals(401, post("127.0.0.1", rig.port, PING, rig.pair()).status)
        }
    }

    @Test
    fun `a changed address rebinds on exactly the new one and frees the old`() = runTest {
        rig().use { rig ->
            rig.controller.start()
            rig.source.emit(a)
            runCurrent()
            assertTrue(accepts(a, rig.port))
            assertFalse(accepts(b, rig.port), "only the tailnet address is bound")

            rig.source.emit(b)
            runCurrent()

            assertTrue(accepts(b, rig.port))
            assertFalse(accepts(a, rig.port), "the old address is released")
        }
    }

    @Test
    fun `the same address reported again does not restart the listener`() = runTest {
        rig().use { rig ->
            rig.controller.start()
            rig.source.emit(a)
            runCurrent()
            val before = rig.logs.count { it.startsWith("Listening") }

            rig.source.emit(a)
            runCurrent()

            assertEquals(before, rig.logs.count { it.startsWith("Listening") })
            assertTrue(accepts(a, rig.port))
        }
    }

    @Test
    fun `a port in use is an error status, and the next address change tries again`() = runTest {
        rig().use { rig ->
            ServerSocket(rig.port, 1, a).use {
                rig.controller.start()
                rig.source.emit(a)
                runCurrent()

                val status = rig.controller.status.value
                assertInstanceOf(TailnetStatus.Error::class.java, status)
                assertTrue("${rig.port}" in (status as TailnetStatus.Error).reason, status.reason)
                assertTrue(rig.controller.running.value, "the session stays on, waiting for a better moment")
            }

            rig.source.emit(null)
            runCurrent()
            rig.source.emit(a)
            runCurrent()

            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)
        }
    }

    @Test
    fun `an address the server refuses is an error, not a crash`() = runTest {
        rig().use { rig ->
            rig.controller.start()

            // The rig relaxed the server's own address guard, so what fails here is the bind itself: this machine
            // does not own that address.
            rig.source.emit(address("203.0.113.9"))
            runCurrent()

            assertInstanceOf(TailnetStatus.Error::class.java, rig.controller.status.value)
        }
    }

    @Test
    fun `stop frees the port and ends the session, and start works again`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()
            assertTrue(accepts(a, rig.port))

            rig.controller.stop()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
            assertFalse(rig.controller.running.value)
            assertFalse(rig.source.started)
            assertFalse(accepts(a, rig.port))

            rig.controller.start()
            runCurrent()
            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)
            assertEquals(2, rig.source.startCount)
        }
    }

    @Test
    fun `an address source that cannot start ends the session with an error`() = runTest {
        rig().use { rig ->
            rig.source.failOnStart = SecurityException("no ACCESS_NETWORK_STATE")

            rig.controller.start()
            runCurrent()

            val status = rig.controller.status.value
            assertInstanceOf(TailnetStatus.Error::class.java, status)
            assertTrue("ACCESS_NETWORK_STATE" in (status as TailnetStatus.Error).reason)
            assertFalse(rig.controller.running.value)
        }
    }

    @Test
    fun `fail ends the session and keeps the reason`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            rig.controller.fail("service not allowed")

            assertEquals(TailnetStatus.Error("service not allowed"), rig.controller.status.value)
            assertFalse(rig.controller.running.value)
            assertFalse(accepts(a, rig.port))
        }
    }

    // ---- Auto-off ------------------------------------------------------------------------------------------------

    @Test
    fun `without any request the switch goes off exactly after the idle period`() = runTest {
        rig(30.minutes).use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            advanceTimeBy(30.minutes - 1.seconds)
            assertTrue(rig.controller.running.value)
            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)

            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
            assertFalse(rig.controller.running.value)
            assertFalse(accepts(a, rig.port))
            assertFalse(rig.source.started)
        }
    }

    @Test
    fun `waiting for the tailnet counts as idle too`() = runTest {
        rig(10.minutes).use { rig ->
            rig.controller.start()
            runCurrent()

            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
        }
    }

    @Test
    fun `an authenticated request restarts the idle period, a refused one does not`() = runTest {
        rig(30.minutes).use { rig ->
            val token = rig.pair()
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            advanceTimeBy(20.minutes)
            assertEquals(401, post("127.0.0.1", rig.port, PING, token = "wrong-token").status)
            advanceTimeBy(9.minutes + 59.seconds)
            assertTrue(rig.controller.running.value, "still inside the period measured from the start")
            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(rig.controller.running.value, "a refused request does not keep it alive")
        }
    }

    @Test
    fun `a valid token keeps the listener alive for a full period from that request`() = runTest {
        rig(30.minutes).use { rig ->
            val token = rig.pair()
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            advanceTimeBy(20.minutes)
            assertNotEquals(401, post("127.0.0.1", rig.port, PING, token).status)

            advanceTimeBy(29.minutes + 59.seconds)
            assertTrue(rig.controller.running.value)
            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(TailnetStatus.Off, rig.controller.status.value)
        }
    }

    @Test
    fun `stopping cancels the idle timer, and a new session gets a fresh period`() = runTest {
        rig(30.minutes).use { rig ->
            rig.controller.start()
            runCurrent()
            advanceTimeBy(25.minutes)
            rig.controller.stop()

            rig.controller.start()
            runCurrent()
            advanceTimeBy(29.minutes)

            assertTrue(rig.controller.running.value)
            advanceTimeBy(1.minutes)
            runCurrent()
            assertFalse(rig.controller.running.value)
        }
    }

    // ---- Denied attempts -----------------------------------------------------------------------------------------

    @Test
    fun `refused requests are reported with their reason and peer`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()
            val seen = mutableListOf<DeniedAttempt>()
            val collector = backgroundScope.launchCollecting(rig.controller.denied, seen)
            runCurrent()

            post("127.0.0.1", rig.port, PING, token = null)
            post("127.0.0.1", rig.port, PING, token = "wrong")
            runCurrent()

            assertEquals(listOf(DenialReason.MISSING_TOKEN, DenialReason.INVALID_TOKEN), seen.map { it.reason })
            assertEquals("127.0.0.1", seen.first().remoteAddress)
            collector.cancel()
        }
    }

    @Test
    fun `starting twice is one session`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            rig.controller.start()
            runCurrent()

            assertEquals(1, rig.source.startCount)
            assertInstanceOf(TailnetStatus.Listening::class.java, rig.controller.status.value)
        }
    }

    // ---- Deep sleep ----------------------------------------------------------------------------------------------

    @Test
    fun `a request after the device slept past the idle period is refused and the session ends`() = runTest {
        rig().use { rig ->
            val token = rig.pair()
            rig.source.initial = a
            rig.controller.start()
            runCurrent()
            assertNotEquals(401, post("127.0.0.1", rig.port, PING, token).status)

            // Deep sleep: the clock moves on, the scheduler's delays (like a phone's) do not.
            rig.sleep(31.minutes)

            assertEquals(401, post("127.0.0.1", rig.port, PING, token).status, "an expired session serves nobody")
            runCurrent()
            assertEquals(TailnetStatus.Off, rig.controller.status.value)
            assertFalse(rig.controller.running.value)
            assertFalse(accepts(a, rig.port))
        }
    }

    @Test
    fun `a refused request after the device slept ends the session too`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            rig.sleep(31.minutes)
            assertEquals(401, post("127.0.0.1", rig.port, PING, "wrong").status)
            runCurrent()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
        }
    }

    @Test
    fun `an address change after the device slept ends the session instead of binding`() = runTest {
        rig().use { rig ->
            rig.source.initial = a
            rig.controller.start()
            runCurrent()

            rig.sleep(31.minutes)
            rig.source.emit(b)
            runCurrent()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
            assertFalse(accepts(b, rig.port))
            assertFalse(accepts(a, rig.port))
        }
    }

    @Test
    fun `the alarm is armed for the deadline, re-armed when there was activity, and cancelled when the session ends`() = runTest {
        rig(30.minutes).use { rig ->
            val token = rig.pair()
            rig.source.initial = a
            rig.controller.start()
            runCurrent()
            assertEquals(listOf(30.minutes.inWholeNanoseconds), rig.alarm.scheduled)

            advanceTimeBy(20.minutes)
            assertNotEquals(401, post("127.0.0.1", rig.port, PING, token).status)
            advanceTimeBy(10.minutes)
            // The alarm fires at the original deadline: there was activity since, so it moves to the new one.
            rig.controller.checkIdle()
            assertEquals(50.minutes.inWholeNanoseconds, rig.alarm.scheduled.last())
            assertTrue(rig.controller.running.value)

            rig.sleep(21.minutes)
            rig.controller.checkIdle()

            assertEquals(TailnetStatus.Off, rig.controller.status.value)
            assertTrue(rig.alarm.cancelled >= 1)
        }
    }

    // ---- Sessions ------------------------------------------------------------------------------------------------

    @Test
    fun `a callback of a stopped source cannot reach the next session`() = runTest {
        rig().use { rig ->
            rig.controller.start()
            runCurrent()
            val old = rig.source.callbacks.single()
            rig.controller.stop()
            rig.controller.start()
            runCurrent()
            assertEquals(2, rig.controller.epoch)

            old(a)
            runCurrent()

            assertEquals(TailnetStatus.WaitingForTailnet, rig.controller.status.value)
            assertFalse(accepts(a, rig.port))
        }
    }

    @Test
    fun `a reconcile that waited for the lock across a restart does not bind the old address`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rig = Rig(scope, virtualNow = { System.nanoTime() / 1_000_000 })
        try {
            val parked = CountDownLatch(1)
            val go = CountDownLatch(1)
            rig.controller.beforeReconcile = {
                rig.controller.beforeReconcile = null
                parked.countDown()
                go.await(5, TimeUnit.SECONDS)
            }
            rig.controller.start()
            rig.source.emit(a)
            assertTrue(parked.await(5, TimeUnit.SECONDS), "the first session's reconcile is about to take the lock")

            rig.controller.stop()
            rig.controller.start()
            go.countDown()
            Thread.sleep(300)

            assertEquals(TailnetStatus.WaitingForTailnet, rig.controller.status.value)
            assertFalse(accepts(a, rig.port), "the old address must not be bound by the new session")
        } finally {
            rig.close()
            scope.cancel()
        }
    }

    @Test
    fun `stopSession ends only the session it names`() = runTest {
        rig().use { rig ->
            rig.controller.start()
            val first = rig.controller.epoch
            rig.controller.stop()
            rig.controller.start()

            rig.controller.stopSession(first)
            assertTrue(rig.controller.running.value, "an old session's number does not end the new session")

            rig.controller.stopSession(rig.controller.epoch)
            assertFalse(rig.controller.running.value)
        }
    }

    @Test
    fun `reject shows the reason without starting anything`() = runTest {
        rig().use { rig ->
            rig.controller.reject("cannot save")

            assertEquals(TailnetStatus.Error("cannot save"), rig.controller.status.value)
            assertFalse(rig.controller.running.value)
            assertFalse(rig.source.started)
        }
    }
}
