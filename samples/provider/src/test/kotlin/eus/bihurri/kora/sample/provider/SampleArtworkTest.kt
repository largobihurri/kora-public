package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.media.MediaArtwork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SampleArtworkTest {
    private val artwork = SampleArtwork(playlistIds = setOf("pl-1", "pl-2"))

    @Test
    fun `every track and known playlist has artwork`() {
        listOf(1, 5, 10, 12_345).forEach { n ->
            assertNotNull(artwork.colourOf(SampleArtwork.trackArtworkId(n)), "track $n")
        }
        assertNotNull(artwork.colourOf(SampleArtwork.playlistArtworkId("pl-1")))
        assertNotNull(artwork.colourOf(SampleArtwork.playlistArtworkId("pl-2")))
    }

    @Test
    fun `neighbouring tracks get different colours`() {
        assertNotEquals(
            artwork.colourOf(SampleArtwork.trackArtworkId(1)),
            artwork.colourOf(SampleArtwork.trackArtworkId(2)),
        )
    }

    @Test
    fun `unknown ids have no artwork`() {
        listOf(
            "nope",
            SampleArtwork.playlistArtworkId("pl-9"),
            SampleArtwork.trackArtworkId(0),
            "art-t-",
            "art-t-abc",
            "art-t-1x",
            "t-1",
        ).forEach { id -> assertNull(artwork.colourOf(id), id) }
    }

    @Test
    fun `the first size tried is the requested edge but never above the native size`() {
        assertEquals(128, SampleArtwork.startEdge(128))
        assertEquals(MediaArtwork.MIN_EDGE_PX, SampleArtwork.startEdge(MediaArtwork.MIN_EDGE_PX))
        assertEquals(SampleArtwork.NATIVE_EDGE_PX, SampleArtwork.startEdge(SampleArtwork.NATIVE_EDGE_PX))
        assertEquals(SampleArtwork.NATIVE_EDGE_PX, SampleArtwork.startEdge(MediaArtwork.MAX_EDGE_PX))
    }

    @Test
    fun `an image under the cap is sent at the first size`() {
        val tried = mutableListOf<Int>()
        val encoded = SampleArtwork.encodeUnderCap(512) { edge -> tried += edge; ByteArray(1_000) }

        assertEquals(listOf(512), tried)
        assertEquals(512, encoded.edgePx)
        assertEquals(1_000, encoded.bytes.size)
    }

    @Test
    fun `an image over the cap steps the size down until it fits`() {
        val tried = mutableListOf<Int>()
        // One byte per pixel: 512 px is 262 144 bytes, far over the 90 KiB cap.
        val encoded = SampleArtwork.encodeUnderCap(512) { edge -> tried += edge; ByteArray(edge * edge) }

        assertTrue(encoded.bytes.size <= MediaArtwork.MAX_ARTWORK_BYTES, "${encoded.bytes.size} bytes")
        assertEquals(tried.last(), encoded.edgePx)
        assertTrue(tried.size > 1)
        assertTrue(tried.zipWithNext().all { (a, b) -> b < a }, "sizes only go down: $tried")
        assertTrue(tried.dropLast(1).all { it * it > MediaArtwork.MAX_ARTWORK_BYTES }, "stops at the first fit: $tried")
    }

    @Test
    fun `an image exactly at the cap fits`() {
        val encoded = SampleArtwork.encodeUnderCap(64) { ByteArray(MediaArtwork.MAX_ARTWORK_BYTES) }

        assertEquals(64, encoded.edgePx)
    }

    @Test
    fun `the size never goes below the contract minimum`() {
        val tried = mutableListOf<Int>()
        assertThrows<IllegalStateException> {
            SampleArtwork.encodeUnderCap(100) { edge -> tried += edge; ByteArray(MediaArtwork.MAX_ARTWORK_BYTES + 1) }
        }

        assertEquals(MediaArtwork.MIN_EDGE_PX, tried.last())
        assertTrue(tried.all { it >= MediaArtwork.MIN_EDGE_PX }, "$tried")
    }

    @Test
    fun `the disc is a lighter shade of the background`() {
        val background = 0xFF204080.toInt()
        val disc = SampleArtwork.discColour(background)

        assertEquals(0xFF, disc ushr 24)
        assertTrue((disc shr 16 and 0xFF) > 0x20)
        assertTrue((disc shr 8 and 0xFF) > 0x40)
        assertTrue((disc and 0xFF) > 0x80)
    }
}
