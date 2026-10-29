package eus.bihurri.kora.android

import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPoolTest {
    private val connector = FakeConnector()
    private val node = FakeRemoteNode()

    private fun TestScope.pool(connect: Int = 5, idle: Int = 30) =
        ConnectionPool(connector, backgroundScope, connect.seconds, idle.seconds)

    private suspend fun assertUnavailable(block: suspend () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (e as KoraException).error.code)
    }

    @Test
    fun `concurrent acquires share one pending connection`() = runTest {
        val pool = pool()
        val first = async { pool.acquire("pkg") }
        val second = async { pool.acquire("pkg") }
        runCurrent()
        assertEquals(1, connector.attempts.size)

        connector.attempts[0].events.onConnected(node)
        runCurrent()

        assertSame(node, first.await().node)
        assertSame(node, second.await().node)
        assertEquals(1, pool.size)
    }

    @Test
    fun `different packages get different connections`() = runTest {
        val pool = pool()
        val a = async { pool.acquire("a") }
        val b = async { pool.acquire("b") }
        runCurrent()
        assertEquals(listOf("a", "b"), connector.attempts.map { it.packageName })
        connector.attempts.forEach { it.events.onConnected(node) }
        a.await()
        b.await()
        assertEquals(2, pool.size)
    }

    @Test
    fun `connect timeout fails as provider unavailable and releases the binding`() = runTest {
        val pool = pool(connect = 5)
        val pending = async { runCatching { pool.acquire("pkg") } }
        runCurrent()

        advanceTimeBy(5.seconds + 1.seconds)
        runCurrent()

        val failure = pending.await().exceptionOrNull() as KoraException
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, failure.error.code)
        assertTrue(connector.attempts[0].unbound)
        assertEquals(0, pool.size)
    }

    @Test
    fun `connecting in time cancels the connect timeout`() = runTest {
        val pool = pool(connect = 5)
        val lease = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        lease.await()

        advanceTimeBy(10.seconds)
        runCurrent()

        assertFalse(connector.attempts[0].unbound)
        assertEquals(1, pool.size)
    }

    @Test
    fun `a refused bind fails immediately`() = runTest {
        val pool = pool()
        connector.refuse = true
        assertUnavailable { pool.acquire("pkg") }
        assertEquals(0, pool.size)
    }

    @Test
    fun `a bind that throws fails as provider unavailable`() = runTest {
        val pool = pool()
        connector.failWith = SecurityException("no")
        assertUnavailable { pool.acquire("pkg") }
        assertEquals(0, pool.size)
    }

    @Test
    fun `losing the binding runs lost listeners and the next acquire reconnects`() = runTest {
        val pool = pool()
        val first = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        val lease = first.await()
        var lost = 0
        lease.onLost { lost++ }

        connector.attempts[0].events.onLost("died")

        assertEquals(1, lost)
        assertTrue(connector.attempts[0].unbound)
        assertEquals(0, pool.size)

        val second = async { pool.acquire("pkg") }
        runCurrent()
        assertEquals(2, connector.attempts.size)
        val fresh = FakeRemoteNode()
        connector.attempts[1].events.onConnected(fresh)
        assertSame(fresh, second.await().node)
    }

    @Test
    fun `a lost listener registered after the loss runs at once and a disposed one never runs`() = runTest {
        val pool = pool()
        val pending = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        val lease = pending.await()
        var disposedRan = false
        lease.onLost { disposedRan = true }.close()
        connector.attempts[0].events.onLost("died")

        var late = false
        lease.onLost { late = true }

        assertFalse(disposedRan)
        assertTrue(late)
    }

    @Test
    fun `waiters of a pending connection fail when it is lost`() = runTest {
        val pool = pool()
        val first = async { runCatching { pool.acquire("pkg") } }
        val second = async { runCatching { pool.acquire("pkg") } }
        runCurrent()

        connector.attempts[0].events.onLost("Peer does not serve a Kora node")

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (first.await().exceptionOrNull() as KoraException).error.code)
        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (second.await().exceptionOrNull() as KoraException).error.code)
    }

    @Test
    fun `an idle connection is unbound after the idle timeout`() = runTest {
        val pool = pool(idle = 30)
        val pending = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        pending.await().release()

        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(connector.attempts[0].unbound)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
        assertEquals(0, pool.size)
    }

    @Test
    fun `a held connection is never unbound`() = runTest {
        val pool = pool(idle = 30)
        val pending = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        val lease = pending.await()

        advanceTimeBy(120.seconds)
        runCurrent()

        assertFalse(connector.attempts[0].unbound)
        lease.release()
        lease.release() // idempotent
        advanceTimeBy(31.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `using the connection again restarts the idle countdown`() = runTest {
        val pool = pool(idle = 30)
        val first = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        first.await().release()

        advanceTimeBy(20.seconds)
        runCurrent()
        val again = pool.acquire("pkg")
        assertEquals(1, connector.attempts.size)
        advanceTimeBy(20.seconds) // 40s after the first release, but the connection is held
        runCurrent()
        assertFalse(connector.attempts[0].unbound)

        again.release()
        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(connector.attempts[0].unbound)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `a connection nobody waits for any more goes idle once it connects`() = runTest {
        val pool = pool(idle = 30)
        val pending = async { pool.acquire("pkg") }
        runCurrent()
        pending.cancel()
        runCurrent()

        connector.attempts[0].events.onConnected(node)
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(connector.attempts[0].unbound)
    }

    @Test
    fun `closing the pool fails holders and refuses new connections`() = runTest {
        val pool = pool()
        val pending = async { pool.acquire("pkg") }
        runCurrent()
        connector.attempts[0].events.onConnected(node)
        var lost = false
        pending.await().onLost { lost = true }

        pool.close()

        assertTrue(lost)
        assertTrue(connector.attempts[0].unbound)
        assertUnavailable { pool.acquire("pkg") }
    }
}
