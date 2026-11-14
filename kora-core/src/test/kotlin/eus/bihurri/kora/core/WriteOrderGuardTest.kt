package eus.bihurri.kora.core

import eus.bihurri.kora.contract.media.WriteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WriteOrderGuardTest {
    private val watch = Caller(CallerKind.PEER, "kardio")
    private val other = Caller(CallerKind.PEER, "koko")
    private val guard = WriteOrderGuard<Caller>()

    @Test
    fun `a write without an order is always admitted`() {
        assertTrue(guard.admit(watch, WriteOrder("w1", 10)))
        assertTrue(guard.admit(watch, null))
        assertTrue(guard.admit(watch, null))
        // An unordered write does not reset the writer: a stale ordered write is still refused.
        assertFalse(guard.admit(watch, WriteOrder("w1", 9)))
    }

    @Test
    fun `the same writer is admitted only with a greater sequence`() {
        assertTrue(guard.admit(watch, WriteOrder("w1", 0)))
        assertTrue(guard.admit(watch, WriteOrder("w1", 5)))
        assertFalse(guard.admit(watch, WriteOrder("w1", 5)), "equal sequence")
        assertFalse(guard.admit(watch, WriteOrder("w1", 3)), "decreasing sequence")
        assertTrue(guard.admit(watch, WriteOrder("w1", 6)))
    }

    @Test
    fun `a new writer replaces the last one and its first write is admitted`() {
        assertTrue(guard.admit(watch, WriteOrder("before-restart", 1_000)))
        assertTrue(guard.admit(watch, WriteOrder("after-restart", 0)), "a reset counter under a new writer")
        assertTrue(guard.admit(watch, WriteOrder("after-restart", 1)))
        assertFalse(guard.admit(watch, WriteOrder("after-restart", 1)))
    }

    @Test
    fun `callers are independent`() {
        assertTrue(guard.admit(watch, WriteOrder("w", 10)))
        assertTrue(guard.admit(other, WriteOrder("w", 1)), "same writer id, another caller")
        assertFalse(guard.admit(watch, WriteOrder("w", 2)))
        assertTrue(guard.admit(other, WriteOrder("w", 2)))
    }

    @Test
    fun `concurrent admits from many coroutines keep the greatest sequence`() = runBlocking {
        val sequences = (0L until 2_000L).shuffled()
        val admitted = coroutineScope {
            sequences.map { sequence ->
                async(Dispatchers.Default) { if (guard.admit(watch, WriteOrder("crown", sequence))) sequence else null }
            }.awaitAll().filterNotNull()
        }

        assertTrue(1_999L in admitted, "the greatest sequence is always admitted")
        assertFalse(guard.admit(watch, WriteOrder("crown", 1_999)), "the guard ends at the greatest sequence")
        assertTrue(guard.admit(watch, WriteOrder("crown", 2_000)))
    }

    @Test
    fun `the least recently seen caller is forgotten beyond the capacity, and starts over`() {
        val small = WriteOrderGuard<String>(maxCallers = 2)
        assertTrue(small.admit("a", WriteOrder("w", 5)))
        assertTrue(small.admit("b", WriteOrder("w", 5)))
        assertFalse(small.admit("a", WriteOrder("w", 4)), "a is still remembered, and now the most recent")
        assertTrue(small.admit("c", WriteOrder("w", 5)), "evicts b")

        assertFalse(small.admit("a", WriteOrder("w", 4)))
        assertTrue(small.admit("b", WriteOrder("w", 1)), "b was forgotten, so its next write is a first write")
    }

    @Test
    fun `the capacity is positive`() {
        assertThrows(IllegalArgumentException::class.java) { WriteOrderGuard<String>(maxCallers = 0) }
    }

    @Test
    fun `applyInOrder applies an admitted write and answers the current state for a stale one`() = runTest {
        var level = 0
        suspend fun write(order: WriteOrder, to: Int) = guard.applyInOrder(watch, order, apply = { level = to; level }, current = { level })

        assertEquals(7, write(WriteOrder("w", 2), 7))
        assertEquals(7, write(WriteOrder("w", 1), 3), "stale: current state unchanged")
        assertEquals(7, level)
        assertEquals(9, write(WriteOrder("w", 3), 9))
        assertEquals(4, guard.applyInOrder(watch, null, apply = { level = 4; level }, current = { level }))
    }

    @Test
    fun `a write whose apply throws still counts and releases the lock`() = runTest {
        var level = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                guard.applyInOrder(watch, WriteOrder("w", 1), apply = { error("player failed") }, current = { level })
            }
        }
        assertEquals("player failed", failure.message)
        // The failed write is the caller's latest: a retry with the same sequence is answered with the current state.
        assertEquals(0, guard.applyInOrder(watch, WriteOrder("w", 1), apply = { level = 9; level }, current = { level }))
        // The lock was released and a greater sequence applies.
        assertEquals(4, guard.applyInOrder(watch, WriteOrder("w", 2), apply = { level = 4; level }, current = { level }))
    }

    @Test
    fun `a late write of a replaced writer is refused`() {
        assertTrue(guard.admit(watch, WriteOrder("old", 50)))
        assertTrue(guard.admit(watch, WriteOrder("new", 0)), "a new session starts over")
        assertFalse(guard.admit(watch, WriteOrder("old", 51)), "the old session's delayed write is stale")
        assertTrue(guard.admit(watch, WriteOrder("new", 1)))
        // Writers of other callers are unaffected by this caller's retired writers.
        assertTrue(guard.admit(other, WriteOrder("old", 51)))
    }

    @Test
    fun `retired writers are remembered up to a bound`() {
        assertTrue(guard.admit(watch, WriteOrder("w0", 0)))
        for (n in 1..WriteOrderGuard.RETIRED_WRITERS + 1) assertTrue(guard.admit(watch, WriteOrder("w$n", 0)))
        // w0 was retired first and has been forgotten; the most recent retired writers are still refused.
        assertTrue(guard.admit(watch, WriteOrder("w0", 1)))
        assertFalse(guard.admit(watch, WriteOrder("w${WriteOrderGuard.RETIRED_WRITERS + 1}", 1)))
    }

    @Test
    fun `concurrent applyInOrder calls leave the level of the greatest sequence`() = runBlocking {
        repeat(20) { round ->
            val guard = WriteOrderGuard<Caller>()
            var level = -1
            val sequences = (0L until 200L).shuffled(kotlin.random.Random(round))
            coroutineScope {
                sequences.map { sequence ->
                    async(Dispatchers.Default) {
                        guard.applyInOrder(watch, WriteOrder("w", sequence), apply = { level = sequence.toInt(); level }, current = { level })
                    }
                }.awaitAll()
            }
            assertEquals(199, level, "round $round")
        }
    }
}
