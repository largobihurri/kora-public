package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The sample's `get_artwork` handler, called through a real node with a fake renderer instead of Android drawing. */
class SampleArtworkProviderTest {
    private val artwork = SampleArtwork(playlistIds = setOf("pl-1"))
    private val renders = mutableListOf<Pair<Int, Int>>()

    private fun node(render: (colour: Int, edgePx: Int) -> ByteArray) = KoraNode("provider").apply {
        provideSampleArtwork(artwork) { colour, edgePx ->
            renders += colour to edgePx
            render(colour, edgePx)
        }
    }

    private fun get(node: KoraNode, artworkId: String, maxEdgePx: Int) = runBlocking {
        node.call(MediaArtwork.GetArtwork, ArtworkRequest(artworkId, maxEdgePx))
    }

    @Test
    fun `a known id answers the rendered image at the requested edge`() {
        val png = byteArrayOf(1, 2, 3)
        val id = SampleArtwork.trackArtworkId(3)

        val image = get(node { _, _ -> png }, id, maxEdgePx = 128)

        assertArrayEquals(png, image.bytes)
        assertEquals(ArtworkMimeType.PNG, image.mimeType)
        assertEquals(128, image.widthPx)
        assertEquals(128, image.heightPx)
        assertEquals(listOf(artwork.colourOf(id)!! to 128), renders)
    }

    @Test
    fun `an unknown id fails with invalid_payload without rendering`() {
        val node = node { _, _ -> byteArrayOf(1) }

        val error = assertThrows<KoraException> { get(node, "art-nope", maxEdgePx = 128) }

        assertEquals(ErrorCode.INVALID_PAYLOAD, error.error.code)
        assertTrue(error.error.message.contains("art-nope"), error.error.message)
        assertEquals(emptyList<Pair<Int, Int>>(), renders)
    }

    @Test
    fun `an image over the byte cap is sent smaller, never upscaled`() {
        val oversized = ByteArray(MediaArtwork.MAX_ARTWORK_BYTES + 1)
        val fits = ByteArray(MediaArtwork.MAX_ARTWORK_BYTES)
        val node = node { _, edgePx -> if (edgePx > 360) oversized else fits }

        val image = get(node, SampleArtwork.playlistArtworkId("pl-1"), maxEdgePx = MediaArtwork.MAX_EDGE_PX)

        assertEquals(listOf(640, 480, 360), renders.map { it.second })
        assertEquals(360, image.widthPx)
        assertEquals(360, image.heightPx)
        assertEquals(MediaArtwork.MAX_ARTWORK_BYTES, image.bytes.size)
    }
}
