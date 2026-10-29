package eus.bihurri.kora.wear

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FrameChunksTest {
    private fun frame(size: Int) = Random(size).nextBytes(size)

    @Test
    fun `a small frame is one chunk and comes back unchanged`() {
        val original = frame(100)

        val chunks = FrameChunks.split(original)

        assertEquals(1, chunks.size)
        assertEquals(original.size + FrameChunks.HEADER_BYTES, chunks.single().size)
        assertArrayEquals(original, FrameAssembler().accept(chunks.single()))
    }

    @Test
    fun `an empty frame is one empty chunk`() {
        val chunks = FrameChunks.split(ByteArray(0))
        assertArrayEquals(ByteArray(0), FrameAssembler().accept(chunks.single()))
    }

    @Test
    fun `a large frame is split under the message limit and reassembled in any order`() {
        val original = frame(WearProtocol.MAX_FRAME_BYTES)

        val chunks = FrameChunks.split(original)

        assertEquals(5, chunks.size)
        assertEquals(true, chunks.all { it.size <= FrameChunks.DEFAULT_CHUNK_PAYLOAD + FrameChunks.HEADER_BYTES })
        val assembler = FrameAssembler()
        val shuffled = chunks.shuffled(Random(7))
        val results = shuffled.map { assembler.accept(it) }
        assertEquals(listOf(null, null, null, null), results.dropLast(1))
        assertArrayEquals(original, results.last())
    }

    @Test
    fun `duplicate chunks are ignored`() {
        val original = frame(150)
        val chunks = FrameChunks.split(original, chunkPayload = 100)
        val assembler = FrameAssembler()

        assertNull(assembler.accept(chunks[0]))
        assertNull(assembler.accept(chunks[0]))
        assertArrayEquals(original, assembler.accept(chunks[1]))
    }

    @Test
    fun `frames whose chunks were lost do not pile up`() {
        val assembler = FrameAssembler(maxPartial = 2)
        val frames = List(3) { FrameChunks.split(frame(250 + it), chunkPayload = 100) }
        frames.forEach { assertNull(assembler.accept(it[0])) } // the first frame is forgotten now

        // The two newest can still be completed; the forgotten one cannot, even with its other chunks.
        assertNull(assembler.accept(frames[2][1]))
        assertArrayEquals(frame(252), assembler.accept(frames[2][2]))
        assertNull(assembler.accept(frames[1][1]))
        assertArrayEquals(frame(251), assembler.accept(frames[1][2]))
        assertNull(assembler.accept(frames[0][1]))
        assertNull(assembler.accept(frames[0][2]))
    }

    @Test
    fun `garbage, other formats and impossible headers are ignored`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(ByteArray(3)))
        assertNull(assembler.accept(ByteArray(20))) // format 0
        val chunk = FrameChunks.split(frame(10)).single()
        chunk[7] = 0
        chunk[8] = 0 // count = 0
        assertNull(assembler.accept(chunk))
    }

    @Test
    fun `a frame over the limit is refused when splitting`() {
        assertThrows(IllegalArgumentException::class.java) { FrameChunks.split(ByteArray(WearProtocol.MAX_FRAME_BYTES + 1)) }
    }

    @Test
    fun `chunks that would exceed the limit when put together are dropped`() {
        val chunks = FrameChunks.split(frame(300), chunkPayload = 100)
        // Same id and count, but the first chunk claims far more bytes than a frame may hold.
        val big = chunks[0].copyOf(FrameChunks.HEADER_BYTES + WearProtocol.MAX_FRAME_BYTES)
        val assembler = FrameAssembler()
        assertNull(assembler.accept(big))
        assertNull(assembler.accept(chunks[1])) // would push the total over the limit
        assertNull(assembler.accept(chunks[2]))
    }
}
