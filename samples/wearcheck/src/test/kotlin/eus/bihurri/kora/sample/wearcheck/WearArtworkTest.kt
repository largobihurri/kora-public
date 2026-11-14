package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The wear check's artwork: ids of this role's stubs, served through a node with a fake renderer. */
class WearArtworkTest {
    private val artwork = WearArtwork(role = "phone", playlistIds = setOf("phone-1", "phone-2"))
    private val renders = mutableListOf<Int>()
    private val node = KoraNode("phone").apply {
        provideWearArtwork(artwork) { _, edgePx ->
            renders += edgePx
            if (edgePx > 300) ByteArray(MediaArtwork.MAX_ARTWORK_BYTES + 1) else byteArrayOf(1, 2)
        }
    }

    private fun get(artworkId: String, maxEdgePx: Int) = runBlocking {
        node.call(MediaArtwork.GetArtwork, ArtworkRequest(artworkId, maxEdgePx))
    }

    @Test
    fun `playlist and track ids of this role are known, others are not`() {
        assertNotNull(artwork.colourOf(WearArtwork.playlistArtworkId("phone-1")))
        assertNotNull(artwork.colourOf(WearArtwork.trackArtworkId("phone", 12)))
        assertNull(artwork.colourOf(WearArtwork.playlistArtworkId("watch-1")))
        assertNull(artwork.colourOf(WearArtwork.trackArtworkId("watch", 1)))
        assertNull(artwork.colourOf(WearArtwork.trackArtworkId("phone", 0)))
        assertNull(artwork.colourOf("art-unknown"))
    }

    @Test
    fun `a known id answers a png, stepped down under the byte cap`() {
        val image = get(WearArtwork.playlistArtworkId("phone-1"), maxEdgePx = 384)

        assertEquals(ArtworkMimeType.PNG, image.mimeType)
        assertEquals(listOf(384, 288), renders)
        assertEquals(288, image.widthPx)
        assertEquals(288, image.heightPx)
    }

    @Test
    fun `an unknown id fails with invalid_payload`() {
        val error = assertThrows<KoraException> { get("art-unknown", maxEdgePx = 384) }

        assertEquals(ErrorCode.INVALID_PAYLOAD, error.error.code)
        assertEquals(emptyList<Int>(), renders)
    }
}
