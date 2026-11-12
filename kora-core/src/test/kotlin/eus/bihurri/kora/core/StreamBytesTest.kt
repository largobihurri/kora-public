package eus.bihurri.kora.core

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** [ByteStream] and [WithBytes]: a receiver reads exactly the declared size, never more and never less. */
class StreamBytesTest {
    @Test
    fun `a receiver reads exactly the declared size`() {
        val bytes = ByteStream(4, TrackedSource("abcd"))
        assertEquals(4L, bytes.sizeBytes)
        assertEquals("abcd", bytes.text())
        assertEquals(-1, bytes.input.read())

        val single = ByteStream(2, TrackedSource("xy")).input
        assertEquals(listOf('x'.code, 'y'.code, -1), List(3) { single.read() })
        assertEquals("", ByteStream(0, TrackedSource("")).text())
    }

    @Test
    fun `a source that ends short fails, and keeps failing`() {
        val input = ByteStream(5, TrackedSource("abc")).input

        val failure = assertThrows(IOException::class.java) { input.readBytes() }
        assertEquals("Byte stream ended after 3 of 5 bytes", failure.message)
        assertThrows(IOException::class.java) { input.read() }
    }

    @Test
    fun `a source that runs over fails when the receiver reads past the size`() {
        val input = ByteStream(3, TrackedSource("abcd")).input
        val buffer = ByteArray(3)

        assertEquals(3, input.readNBytes(buffer, 0, 3))
        assertEquals("abc", buffer.decodeToString())
        val failure = assertThrows(IOException::class.java) { input.read() }
        assertEquals("Byte stream runs over its 3 bytes", failure.message)
        assertThrows(IOException::class.java) { input.read(buffer, 0, 3) }
        assertThrows(IOException::class.java) { ByteStream(3, TrackedSource("abcd")).input.readBytes() }
    }

    @Test
    fun `a size violation throws ByteStreamSizeException, and keeps throwing it`() {
        val short = ByteStream(5, TrackedSource("abc")).input
        val over = ByteStream(3, TrackedSource("abcd")).input

        val shortFailure = assertThrows(ByteStreamSizeException::class.java) { short.readBytes() }
        assertThrows(ByteStreamSizeException::class.java) { short.read() }
        over.readNBytes(3)
        val overFailure = assertThrows(ByteStreamSizeException::class.java) { over.read() }
        assertThrows(ByteStreamSizeException::class.java) { over.read(ByteArray(3), 0, 3) }

        assertEquals("Byte stream ended after 3 of 5 bytes", shortFailure.message)
        assertEquals("Byte stream runs over its 3 bytes", overFailure.message)
        // It stays an IOException, so the catch blocks of the tests above (and of providers) still catch it.
    }

    @Test
    fun `a negative size is refused`() {
        val failure = assertThrows(IllegalArgumentException::class.java) { ByteStream(-1, TrackedSource("")) }
        assertEquals("sizeBytes must not be negative: -1", failure.message)
    }

    @Test
    fun `a streamed response carries bytes exactly when it succeeds`() {
        val source = TrackedSource("ab")
        StreamedResponse(KoraResponse.Success("r", ByteArray(0)), ByteStream(2, source)).close()
        assertEquals(1, source.closes)
        StreamedResponse(KoraResponse.Failure("r", KoraError(ErrorCode.INTERNAL, "x")), null).close()

        assertThrows(IllegalArgumentException::class.java) { StreamedResponse(KoraResponse.Success("r", ByteArray(0)), null) }
        assertThrows(IllegalArgumentException::class.java) {
            StreamedResponse(KoraResponse.Failure("r", KoraError(ErrorCode.INTERNAL, "x")), ByteStream(0, TrackedSource("")))
        }
    }

    @Test
    fun `closing closes the source once, and WithBytes closes its bytes`() {
        val source = TrackedSource("ab")
        val bytes = ByteStream(2, source)
        bytes.close()
        bytes.close()
        assertEquals(1, source.closes)

        val other = TrackedSource("ab")
        WithBytes("out", ByteStream(2, other)).use { assertEquals("out", it.output) }
        assertEquals(1, other.closes)
    }

    @Test
    fun `closing from several threads at once closes the source once`() {
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(20_000) { round ->
                val closes = AtomicInteger()
                val source = object : InputStream() {
                    override fun read(): Int = -1

                    override fun close() {
                        closes.incrementAndGet()
                    }
                }
                val bytes = ByteStream(0, source)
                val arrived = AtomicInteger()

                // A spinning start lines the threads up far more tightly than a parking barrier.
                List(threads) {
                    pool.submit {
                        arrived.incrementAndGet()
                        while (arrived.get() < threads) Thread.onSpinWait()
                        bytes.close()
                    }
                }.forEach { it.get() }

                assertEquals(1, closes.get(), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
