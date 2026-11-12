package eus.bihurri.kora.android

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.core.ByteStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The pieces a stream is moved with, on real threads and real time: the pump, the watched input, the watchdog. */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class BinderStreamsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val descriptors = FakeDescriptors(capacity = 1024)

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun content(size: Int) = ByteArray(size) { (it * 7).toByte() }

    /** Reads everything [descriptor] carries on another thread, so a pump that blocks on a full pipe can go on. */
    private fun drain(descriptor: FakeDescriptor): () -> ByteArray {
        var result: ByteArray? = null
        val reader = thread { result = descriptors.input(descriptor).use { it.readAllBytes() } }
        return {
            reader.join()
            result!!
        }
    }

    @Test
    fun `the pump copies every byte and closes the sink`() {
        val data = content(10_000)
        val pipe = descriptors.createPipe()
        val received = drain(pipe.readEnd as FakeDescriptor)

        val result = pump(ByteStream(10_000, ByteArrayInputStream(data)).input, 10_000, descriptors.output(pipe.writeEnd), IdleWatchdog(5.seconds))

        assertEquals(PumpResult.Done, result)
        assertArrayEquals(data, received())
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a source that ends short stops the pump and leaves the receiver short`() {
        val pipe = descriptors.createPipe()
        val received = drain(pipe.readEnd as FakeDescriptor)

        val result = pump(ByteStream(10_000, ByteArrayInputStream(content(6_000))).input, 10_000, descriptors.output(pipe.writeEnd), IdleWatchdog(5.seconds))

        assertInstanceOf(PumpResult.SourceFailed::class.java, result)
        assertEquals(6_000, received().size)
    }

    @Test
    fun `a source that runs over is forwarded one byte past its size so the receiver sees it too`() {
        val pipe = descriptors.createPipe()
        val receiver = ByteStream(4_000, descriptors.input(pipe.readEnd))
        var failure: Throwable? = null
        val reader = thread { failure = runCatching { receiver.input.readAllBytes() }.exceptionOrNull() }

        val result = pump(ByteStream(4_000, ByteArrayInputStream(content(5_000))).input, 4_000, descriptors.output(pipe.writeEnd), IdleWatchdog(5.seconds))
        reader.join()

        assertInstanceOf(PumpResult.SourceFailed::class.java, result)
        assertTrue(failure is IOException && failure!!.message!!.contains("runs over"), "receiver failure: $failure")
        receiver.close()
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a sink nobody reads any more fails the pump`() {
        val pipe = descriptors.createPipe()
        pipe.readEnd.close()

        val result = pump(ByteStream(10, ByteArrayInputStream(content(10))).input, 10, descriptors.output(pipe.writeEnd), IdleWatchdog(5.seconds))

        assertInstanceOf(PumpResult.SinkFailed::class.java, result)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a steady reader slower than a whole pipe per timeout is not cut while chunks keep moving`() {
        // A pipe of 8 KiB stands in for the system's 64 KiB one, so the test needs 40 KiB rather than hundreds.
        val small = FakeDescriptors(capacity = 8 * 1024)
        val pipe = small.createPipe()
        val data = content(40 * 1024)
        val idle = 600.milliseconds
        val watchdog = IdleWatchdog(idle).apply { startWaiting() } // as the pumps of both sides set it up
        val sink = small.output(pipe.writeEnd)
        val guard = scope.launch {
            watchdog.awaitIdle()
            sink.close() // what the guard does: it stops the pump
        }
        var received: ByteArray? = null
        val reader = thread {
            val input = small.input(pipe.readEnd)
            val all = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (true) {
                Thread.sleep(idle.inWholeMilliseconds / 2) // 8 KiB per half idle period: slow, but never stalled
                val count = input.readNBytes(chunk, 0, chunk.size)
                if (count == 0) break
                all.write(chunk, 0, count)
            }
            input.close()
            received = all.toByteArray()
        }

        val result = pump(ByteStream(data.size.toLong(), ByteArrayInputStream(data)).input, data.size.toLong(), sink, watchdog)
        reader.join()
        guard.cancel()

        assertEquals(PumpResult.Done, result)
        assertArrayEquals(data, received)
        assertTrue(small.stillOpen().isEmpty())
    }

    /** How long [watchdog] takes to find the transfer idle, from [start]. */
    private fun idleAfter(watchdog: IdleWatchdog, start: TimeMark): Duration {
        runBlocking { watchdog.awaitIdle() }
        return start.elapsedNow()
    }

    @Test
    fun `a pump waiting for room is held to the watchdog's timeout, not to its source timeout`() {
        val pipe = descriptors.createPipe() // nobody reads it, so the pump fills it and waits for room
        val sink = descriptors.output(pipe.writeEnd)
        val watchdog = IdleWatchdog(200.milliseconds).apply { startWaiting() }
        val start = TimeSource.Monotonic.markNow()
        var result: PumpResult? = null
        val pumping = thread {
            result = pump(ByteStream(100_000, ByteArrayInputStream(content(100_000))).input, 100_000, sink, watchdog, sourceTimeout = 10.seconds)
        }

        val idle = idleAfter(watchdog, start)
        sink.close() // what the guard does: it stops the pump
        pumping.join()
        pipe.readEnd.close()

        assertTrue(idle < 5.seconds, "found idle after $idle")
        assertInstanceOf(PumpResult.SinkFailed::class.java, result)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a pump waiting for its source is allowed its source timeout`() {
        val pipe = descriptors.createPipe()
        val received = drain(pipe.readEnd as FakeDescriptor)
        val source = StalledInput(content(10))
        val watchdog = IdleWatchdog(100.milliseconds).apply { startWaiting() }
        val start = TimeSource.Monotonic.markNow()
        var result: PumpResult? = null
        val pumping = thread {
            result = pump(ByteStream(1_000, source).input, 1_000, descriptors.output(pipe.writeEnd), watchdog, sourceTimeout = 600.milliseconds)
        }

        val idle = idleAfter(watchdog, start)
        source.close() // what the guard does: it stops the pump
        pumping.join()

        assertTrue(idle >= 500.milliseconds, "found idle after $idle")
        assertInstanceOf(PumpResult.SourceFailed::class.java, result)
        assertEquals(10, received().size)
        assertTrue(descriptors.stillOpen().isEmpty())
    }

    @Test
    fun `a watchdog allowed longer waits that long while no byte moves`() = runTest {
        val watchdog = IdleWatchdog(1.seconds, testScheduler.timeSource).apply { startWaiting() }
        watchdog.allowIdle(3.seconds)

        val idleAt = async { watchdog.awaitIdle().let { currentTime } }

        assertEquals(3_000L, idleAt.await())
    }

    @Test
    fun `a byte that moves cuts a longer allowance back to the timeout, counted from that byte`() = runTest {
        val watchdog = IdleWatchdog(1.seconds, testScheduler.timeSource).apply { startWaiting() }
        watchdog.allowIdle(3.seconds)
        val idleAt = async { watchdog.awaitIdle().let { currentTime } }
        advanceTimeBy(500.milliseconds)

        watchdog.moved()

        assertEquals(1_500L, idleAt.await())
    }

    @Test
    fun `a read that waits too long fails with timeout and closes the source`() {
        val source = StalledInput(content(10))
        val input = WatchedInput(source, 200.milliseconds, scope)
        val buffer = ByteArray(100)

        assertEquals(10, input.read(buffer))
        val failure = assertThrows(KoraException::class.java) { input.read(buffer) }

        assertEquals(ErrorCode.TIMEOUT, failure.error.code)
        assertTrue(source.closed)
        assertEquals(ErrorCode.TIMEOUT, assertThrows(KoraException::class.java) { input.read() }.error.code)
    }

    @Test
    fun `an input nobody is reading does not time out`() {
        val input = WatchedInput(ByteArrayInputStream(content(10)), 100.milliseconds, scope)

        Thread.sleep(400)

        assertArrayEquals(content(10), input.readAllBytes())
    }

    @Test
    fun `failing an input unblocks the read that waits with that error`() {
        val source = StalledInput()
        val input = WatchedInput(source, 30.seconds, scope)
        var failure: Throwable? = null
        val reader = thread { failure = runCatching { input.read() }.exceptionOrNull() }
        Thread.sleep(100)

        input.fail(KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "gone")))
        reader.join()

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, (failure as KoraException).error.code)
        assertTrue(source.closed)
    }

    @Test
    fun `an input reports its end once, at the end of the bytes or when closed`() {
        val ends = AtomicInteger()
        val input = WatchedInput(ByteArrayInputStream(content(3)), 30.seconds, scope) { ends.incrementAndGet() }

        assertEquals(3, input.readAllBytes().size)
        input.close()
        input.close()

        assertEquals(1, ends.get())
        val closedEarly = AtomicInteger()
        WatchedInput(ByteArrayInputStream(content(3)), 30.seconds, scope) { closedEarly.incrementAndGet() }.close()
        assertEquals(1, closedEarly.get())
    }

    @Test
    fun `an input whose scope is already closed fails at once and is closed`() {
        val closed = CoroutineScope(SupervisorJob()).apply { cancel() }
        val source = StalledInput()

        val input = WatchedInput(source, 30.seconds, closed)

        assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, assertThrows(KoraException::class.java) { input.read() }.error.code)
        assertTrue(source.closed)
    }
}
