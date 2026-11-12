package eus.bihurri.kora.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bytes that travel beside the envelope of a stream operation (see `OperationSpec.stream`), in either
 * direction: what a caller hands to `KoraNode.callSending` and an upload handler receives, and what a download
 * handler offers and `KoraNode.callReceiving` answers.
 *
 * [sizeBytes] is declared up front and travels with the stream, not in the typed input, so the node stays generic;
 * a provider that also receives a size in its input cross-checks the two itself. Whoever holds a [ByteStream]
 * closes it: the node closes an upload once the handler is done, and the receiver of a download closes it.
 *
 * Reads block (`java.io`) and do not react to coroutine cancellation; closing the stream is what stops a reader.
 * Read off the caller's dispatcher, for example in `withContext(Dispatchers.IO)`, so that a slow source does not hold
 * a thread meant for something else (a handler runs in the calling coroutine, on whatever dispatcher it uses).
 *
 * A source that ends short fails the read that reaches its end. A source that runs over fails only once the
 * receiver reads past [sizeBytes], so a receiver that wants the size checked reads to the end of [input] (until it
 * answers -1), as `readAllBytes` and `transferTo` do; one that stops after exactly [sizeBytes] bytes never learns
 * that the source had more. Either failure is a [ByteStreamSizeException]. When it comes from an upload's own bytes
 * and escapes the provider's handler, the node answers `invalid_payload` (the caller sent a malformed stream), not
 * `internal`.
 *
 * @param sizeBytes exact number of bytes the stream carries; not negative.
 * @param input the source of the bytes. Read it through the [input] property, never directly.
 */
public class ByteStream(public val sizeBytes: Long, input: InputStream) : Closeable {
    init {
        require(sizeBytes >= 0) { "sizeBytes must not be negative: $sizeBytes" }
    }

    private val exact = ExactSizeInputStream(input, sizeBytes)

    /**
     * What a receiver reads: exactly [sizeBytes] bytes of the source, then end of stream. A read throws
     * [ByteStreamSizeException] when the source ends before [sizeBytes] bytes, or when it still has bytes after
     * them; to find the overrun, the first read past the size reads one more byte from the source, so only a
     * receiver that reads to the end finds it. Once it has failed it keeps failing. Other failures of the source
     * pass through as they are. Closing it closes the source, once.
     */
    public val input: InputStream = exact

    /** True once a read of [input] has found that the source does not carry [sizeBytes] bytes. */
    internal val sizeViolated: Boolean get() = exact.violated

    /** Closes the source. Closing again does nothing, even from several threads at once: the source closes once. */
    override fun close() {
        input.close()
    }

    override fun toString(): String = "ByteStream($sizeBytes bytes)"
}

/**
 * The source of a [ByteStream] does not carry the declared size: it ended short, or it still had bytes after
 * `sizeBytes`. Thrown by reads of [ByteStream.input]. It is an [IOException], so code that catches those still
 * catches it.
 *
 * Over a transport, a size violation means the sender sent a malformed stream. So when one found in an upload's
 * own bytes escapes the provider's handler, `KoraNode.handleSending` answers `invalid_payload` with its message and
 * does not report it to `onInternalError`; a size violation of any other stream the handler reads (one it
 * downloaded itself, say) is still `internal`.
 */
public class ByteStreamSizeException internal constructor(message: String) : IOException(message)

/** The typed [output] of a download and the [bytes] that come with it. Closing it closes [bytes]. */
public class WithBytes<out O>(public val output: O, public val bytes: ByteStream) : Closeable {
    override fun close() {
        bytes.close()
    }
}

/** Closes this, ignoring the [IOException] a source may throw while closing: there is nothing left to do about it. */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Already done with the bytes either way.
    }
}

/** Reads exactly [size] bytes of [source] and fails when the source ends short or runs over. */
private class ExactSizeInputStream(private val source: InputStream, private val size: Long) : InputStream() {
    private var remaining = size

    @Volatile
    private var failure: String? = null
    private var atEnd = false
    private val closed = AtomicBoolean(false)

    /** A read found that the source does not carry [size] bytes. */
    val violated: Boolean get() = failure != null

    override fun read(): Int {
        if (remaining == 0L) return end()
        failure?.let { throw ByteStreamSizeException(it) }
        val byte = source.read()
        if (byte < 0) throw fail("Byte stream ended after ${size - remaining} of $size bytes")
        remaining--
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        if (remaining == 0L) return end()
        failure?.let { throw ByteStreamSizeException(it) }
        val count = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (count < 0) throw fail("Byte stream ended after ${size - remaining} of $size bytes")
        remaining -= count
        return count
    }

    override fun available(): Int = if (failure != null) 0 else minOf(source.available().toLong(), remaining).toInt()

    /** Only the first close, from whichever thread, reaches the source. */
    override fun close() {
        if (closed.compareAndSet(false, true)) source.close()
    }

    /** Every byte was read: the source must be at its end too. */
    private fun end(): Int {
        failure?.let { throw ByteStreamSizeException(it) }
        if (!atEnd) {
            if (source.read() >= 0) throw fail("Byte stream runs over its $size bytes")
            atEnd = true
        }
        return -1
    }

    private fun fail(message: String): ByteStreamSizeException {
        failure = message
        return ByteStreamSizeException(message)
    }
}
