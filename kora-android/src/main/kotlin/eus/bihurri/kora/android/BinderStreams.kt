package eus.bihurri.kora.android

import android.os.ParcelFileDescriptor
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// The pieces both sides of a Binder stream are built from (IKoraNode.callSending and callReceiving, protocol
// version 2): the descriptors, the pump that copies bytes into a pipe, the input that reads one, and the watchdog that
// ends a stream that stalls. Only [Descriptors] touches Android types, so the rest runs in plain JVM tests.

/** Default for `streamIdleTimeout`: how long a stream may stall before it fails with `timeout`. */
internal val DEFAULT_STREAM_IDLE_TIMEOUT: Duration = 10.seconds

/**
 * Bytes moved per read and write by [pump]. A write into a full pipe returns only once all of it is written, and the
 * watchdog hears of progress only then, so the chunk is kept well below a pipe's size (64 KiB on Linux): a reader that
 * is slow but steady shows progress every 8 KiB rather than being cut as stalled.
 */
private const val PUMP_CHUNK_BYTES = 8 * 1024

/** The two ends of a pipe: [readEnd] is handed to the other side in a transaction, [writeEnd] stays to be written. */
internal class Pipe(val readEnd: ParcelFileDescriptor, val writeEnd: ParcelFileDescriptor)

/**
 * Port: creates pipes and opens descriptors as `java.io` streams. Closing a stream opened here closes its descriptor.
 * Tests replace it with in-memory pipes; Robolectric cannot, as its pipes are temporary files that never block.
 */
internal interface Descriptors {
    /** A new pipe. Throws [IOException] when the system cannot open one (for example, out of descriptors). */
    fun createPipe(): Pipe

    /** Reads [descriptor]; closing the stream closes the descriptor, and unblocks a read that waits on it. */
    fun input(descriptor: ParcelFileDescriptor): InputStream

    /** Writes into [descriptor]; closing the stream closes the descriptor, and unblocks a write that waits on it. */
    fun output(descriptor: ParcelFileDescriptor): OutputStream
}

/** [Descriptors] of the platform: `ParcelFileDescriptor.createPipe` and the auto-closing streams. */
internal object AndroidDescriptors : Descriptors {
    override fun createPipe(): Pipe = ParcelFileDescriptor.createPipe().let { (read, write) -> Pipe(read, write) }

    override fun input(descriptor: ParcelFileDescriptor): InputStream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)

    override fun output(descriptor: ParcelFileDescriptor): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
}

/** Closes this, ignoring the [IOException] closing may throw: the descriptor is released either way. */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Nothing left to do with it.
    }
}

/**
 * Tells when a transfer has stalled: it is idle once it has been **waiting** for [timeout] while no byte moved.
 *
 * Waiting is what the side that owns the watchdog cannot help: a pump or a call is waiting for as long as it runs
 * ([startWaiting] once), a reader only while one of its reads is blocked ([waiting]). So a reader that is busy with
 * something else between reads is never cut, while one blocked on a stalled peer is. Every byte that moves calls
 * [moved]. A transfer may be allowed longer than [timeout] until the next byte moves ([allowIdle]). [timeSource] is the
 * clock; tests pass the virtual one of their scheduler.
 */
internal class IdleWatchdog(
    private val timeout: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val lock = Any()
    private var waiters = 0
    private var since: TimeMark = timeSource.markNow()
    private var limit: Duration = timeout

    /** A byte moved: the idle time starts again, and may last [timeout]. */
    fun moved() {
        synchronized(lock) {
            since = timeSource.markNow()
            limit = timeout
        }
    }

    /**
     * Until the next byte moves, lets the transfer stay idle for [longer] instead of [timeout]; the idle time still
     * counts from the last byte. A [longer] below [timeout] counts as [timeout].
     */
    fun allowIdle(longer: Duration) {
        synchronized(lock) { limit = maxOf(timeout, longer) }
    }

    /** From now on the transfer is waiting, until a matching [stopWaiting]. */
    fun startWaiting() {
        synchronized(lock) { if (waiters++ == 0) since = timeSource.markNow() }
    }

    fun stopWaiting() {
        synchronized(lock) { waiters-- }
    }

    /** Runs [block], a call that may block on the other side, as waiting. */
    inline fun <T> waiting(block: () -> T): T {
        startWaiting()
        try {
            return block()
        } finally {
            stopWaiting()
        }
    }

    /** Suspends until the transfer has been waiting for [timeout] with no byte moving, then returns. */
    suspend fun awaitIdle() {
        while (true) {
            val left = synchronized(lock) { if (waiters == 0) limit else limit - since.elapsedNow() }
            if (left <= Duration.ZERO) return
            // Never asleep for more than [timeout] at once: when [moved] cuts a longer limit back to [timeout], the
            // new end, [timeout] after that byte, is then still kept to the moment.
            delay(if (timeout > Duration.ZERO) minOf(left, timeout) else left)
        }
    }
}

/**
 * Reads a stream that arrives through a descriptor ([source]), guarded so that the read never hangs for good:
 *
 * - A read that waits [timeout] for a byte fails with `timeout`: the watchdog closes [source], which is what unblocks
 *   a read blocked on a descriptor, and the read throws the [KoraException]. A reader that simply does not read is
 *   never cut (see [IdleWatchdog]).
 * - [fail] does the same with another error, for example `provider_unavailable` when the peer dies.
 * - When [scope], which runs the watchdog, ends (the service or the transport closes), the input fails with
 *   `provider_unavailable`.
 *
 * Once failed, every read throws the same error. [onFinish] runs once, on the first of: the end of the bytes, a read
 * that fails, [fail], or [close]. Reads throw [KoraException], unchecked, besides the `IOException` of the source.
 */
internal class WatchedInput(
    private val source: InputStream,
    timeout: Duration,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val onFinish: () -> Unit = {},
) : InputStream() {
    private val watchdog = IdleWatchdog(timeout, timeSource)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var failure: KoraException? = null

    private val guard: Job = scope.launch(start = CoroutineStart.LAZY) {
        watchdog.awaitIdle()
        fail(KoraException(KoraError(ErrorCode.TIMEOUT, "No byte of the stream arrived for $timeout")))
    }

    init {
        guard.invokeOnCompletion {
            if (!finished.get()) fail(KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The stream was closed by its transport")))
        }
        guard.start()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        failure?.let { throw it }
        if (len == 0) return 0
        val count = try {
            watchdog.waiting { source.read(b, off, len) }
        } catch (e: IOException) {
            failure?.let { throw it } // our own closing made the read fail
            finish()
            throw e
        }
        failure?.let { throw it }
        if (count < 0) finish() else watchdog.moved()
        return count
    }

    override fun available(): Int = if (failure != null) {
        0
    } else {
        try {
            source.available()
        } catch (_: IOException) {
            0
        }
    }

    /** Ends the stream with [error]: [source] is closed and every read, the waiting one included, throws [error]. */
    fun fail(error: KoraException) {
        if (finished.get()) return
        failure = error
        finish()
        source.closeQuietly()
    }

    override fun close() {
        finish()
        source.close()
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        guard.cancel()
        onFinish()
    }
}

/** How a [pump] ended. */
internal sealed interface PumpResult {
    /** Every byte was written and the source ended where it should. */
    data object Done : PumpResult

    /** Reading the source failed: it ended short, ran over or broke. The receiver sees a stream it rejects. */
    class SourceFailed(val error: Exception) : PumpResult

    /** Writing failed: the receiver closed its end, or the sink was closed to stop the pump. */
    class SinkFailed(val error: IOException) : PumpResult
}

/**
 * Copies [source] into [sink] and closes [sink], on every path; blocking, so it runs off Binder threads and off the
 * caller's dispatcher. [source] is the size-checked input of a `ByteStream` of [sizeBytes] bytes, read to its end so
 * that an overrun is found. Every chunk read or written (at most [PUMP_CHUNK_BYTES]) feeds [watchdog]. While the
 * pump waits for [source], the watchdog allows [sourceTimeout] when one is given ([IdleWatchdog.allowIdle]); while it
 * waits for room in [sink], its own timeout.
 *
 * A source that runs over fails only after all [sizeBytes] were written, which the receiver would accept as a whole
 * stream; so in that case one more byte is written before closing, and the receiver, which reads exactly
 * [sizeBytes], finds the overrun too. A source that ends short just leaves the receiver short.
 *
 * Closing [sink] from another thread is how a pump that waits for room is stopped; the source is closed by its owner.
 */
internal fun pump(
    source: InputStream,
    sizeBytes: Long,
    sink: OutputStream,
    watchdog: IdleWatchdog,
    sourceTimeout: Duration? = null,
): PumpResult {
    val buffer = ByteArray(PUMP_CHUNK_BYTES)
    var written = 0L
    try {
        while (true) {
            sourceTimeout?.let(watchdog::allowIdle)
            val count = try {
                source.read(buffer)
            } catch (e: Exception) {
                if (written == sizeBytes) {
                    try {
                        sink.write(0)
                    } catch (_: IOException) {
                        // The receiver is gone; it rejects nothing any more.
                    }
                }
                return PumpResult.SourceFailed(e)
            }
            if (count < 0) return PumpResult.Done
            watchdog.moved()
            try {
                sink.write(buffer, 0, count)
            } catch (e: IOException) {
                return PumpResult.SinkFailed(e)
            }
            written += count
            watchdog.moved()
        }
    } finally {
        sink.closeQuietly()
    }
}

/** The error a caller gets when the bytes it hands over fail: as is when it is a [KoraException]. */
internal fun sourceFailure(error: Exception): KoraException =
    error as? KoraException
        ?: KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "The byte stream to send failed: ${error.message}"), error)
