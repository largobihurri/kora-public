package eus.bihurri.kora.contract

import eus.bihurri.kora.contract.fixtures.toHex
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.ArtworkRequest
import eus.bihurri.kora.contract.media.MediaArtwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaArtworkTest {
    private val spec = MediaArtwork.GetArtwork

    private fun image(size: Int, mimeType: ArtworkMimeType = ArtworkMimeType.JPEG) =
        ArtworkImage(ByteArray(size) { (it * 7).toByte() }, mimeType, widthPx = 512, heightPx = 384)

    // ---- Request ----------------------------------------------------------------------------------------------

    @Test
    fun `a request needs a non-blank artwork id`() {
        assertThrows(IllegalArgumentException::class.java) { ArtworkRequest("") }
        assertThrows(IllegalArgumentException::class.java) { ArtworkRequest("   ") }
        assertEquals("art-1", ArtworkRequest("art-1").artworkId)
    }

    @Test
    fun `the requested edge has a default and stays within the documented bounds`() {
        assertEquals(MediaArtwork.DEFAULT_EDGE_PX, ArtworkRequest("art-1").maxEdgePx)
        assertTrue(MediaArtwork.DEFAULT_EDGE_PX in MediaArtwork.MIN_EDGE_PX..MediaArtwork.MAX_EDGE_PX)
        assertEquals(MediaArtwork.MIN_EDGE_PX, ArtworkRequest("art-1", MediaArtwork.MIN_EDGE_PX).maxEdgePx)
        assertEquals(MediaArtwork.MAX_EDGE_PX, ArtworkRequest("art-1", MediaArtwork.MAX_EDGE_PX).maxEdgePx)
        assertThrows(IllegalArgumentException::class.java) { ArtworkRequest("art-1", MediaArtwork.MIN_EDGE_PX - 1) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkRequest("art-1", MediaArtwork.MAX_EDGE_PX + 1) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkRequest("art-1", 0) }
    }

    @Test
    fun `a request decoded from the wire is validated too`() {
        assertThrows(IllegalArgumentException::class.java) { spec.decodeInput(JsonCodec, """{"artworkId":" "}""".encodeToByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            spec.decodeInput(JsonCodec, """{"artworkId":"a","maxEdgePx":${MediaArtwork.MAX_EDGE_PX + 1}}""".encodeToByteArray())
        }
        assertEquals(ArtworkRequest("a"), spec.decodeInput(JsonCodec, """{"artworkId":"a"}""".encodeToByteArray()))
    }

    // ---- Image ------------------------------------------------------------------------------------------------

    @Test
    fun `an image is not empty and never exceeds the contract cap`() {
        assertThrows(IllegalArgumentException::class.java) { image(0) }
        assertThrows(IllegalArgumentException::class.java) { image(MediaArtwork.MAX_ARTWORK_BYTES + 1) }
        assertEquals(MediaArtwork.MAX_ARTWORK_BYTES, image(MediaArtwork.MAX_ARTWORK_BYTES).bytes.size)
        assertEquals(1, image(1).bytes.size)
    }

    @Test
    fun `an image has positive dimensions`() {
        val bytes = byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { ArtworkImage(bytes, ArtworkMimeType.PNG, 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkImage(bytes, ArtworkMimeType.PNG, 10, -1) }
    }

    @Test
    fun `images compare by content`() {
        assertEquals(image(10), image(10))
        assertEquals(image(10).hashCode(), image(10).hashCode())
        assertNotEquals(image(10), image(11))
        assertNotEquals(image(10), image(10, ArtworkMimeType.PNG))
        assertTrue(image(10).toString().contains("10 bytes"))
    }

    @Test
    fun `the MIME types are a closed set read tolerantly`() {
        assertEquals(listOf("image/jpeg", "image/png", "image/webp"), WireEnums.allowedValues(ArtworkMimeType.serializer().descriptor))
        val future = """{"bytes":"AQ==","mimeType":"image/avif","widthPx":1,"heightPx":1}"""
        assertEquals(ArtworkMimeType.UNKNOWN, spec.decodeOutput(JsonCodec, future.encodeToByteArray()).mimeType)
    }

    // ---- Encoding of the bytes --------------------------------------------------------------------------------

    @Test
    fun `bytes are a standard Base64 string in JSON`() {
        val sample = ArtworkImage(byteArrayOf(-1, 0, 1, 2), ArtworkMimeType.PNG, 2, 1)
        val json = spec.encodeOutput(JsonCodec, sample).decodeToString()

        assertEquals("""{"bytes":"/wABAg==","mimeType":"image/png","widthPx":2,"heightPx":1}""", json)
        assertEquals(sample, spec.decodeOutput(JsonCodec, json.encodeToByteArray()))
    }

    @Test
    fun `invalid Base64 in JSON is rejected`() {
        for (bad in listOf("not base64!", "AQ", "[1,2]")) {
            val text = if (bad.startsWith("[")) bad else "\"$bad\""
            assertThrows(IllegalArgumentException::class.java) {
                spec.decodeOutput(JsonCodec, """{"bytes":$text,"mimeType":"image/png","widthPx":1,"heightPx":1}""".encodeToByteArray())
            }
        }
    }

    @Test
    fun `bytes are a CBOR byte string between peers`() {
        val sample = ArtworkImage(byteArrayOf(1, 2, 3), ArtworkMimeType.WEBP, 1, 1)
        val cbor = spec.encodeOutput(CborCodec, sample)

        // "bytes" (text string of 5) followed by major type 2 with length 3: 0x43 01 02 03.
        assertTrue(cbor.toHex().contains("65" + "bytes".encodeToByteArray().toHex() + "43010203"), cbor.toHex())
        assertEquals(sample, spec.decodeOutput(CborCodec, cbor))
    }

    // ---- The cap fits every transport -------------------------------------------------------------------------

    @Test
    fun `the largest image fits one CBOR peer envelope`() {
        val largest = image(MediaArtwork.MAX_ARTWORK_BYTES, ArtworkMimeType.WEBP)
        val envelope = KoraResponse.Success("r".repeat(128), spec.encodeOutput(CborCodec, largest))

        val encoded = CborCodec.encode(KoraResponse.serializer(), envelope)

        assertTrue(encoded.size < TRANSPORT_LIMIT, "CBOR envelope of ${encoded.size} bytes")
    }

    @Test
    fun `an MCP answer holding the largest image twice in Base64 plus overhead fits the transport`() {
        val largest = image(MediaArtwork.MAX_ARTWORK_BYTES, ArtworkMimeType.WEBP)
        val json = spec.encodeOutput(JsonCodec, largest)
        val base64 = Json.parseToJsonElement(json.decodeToString()).jsonObject["bytes"]!!.jsonPrimitive.content

        assertEquals(4 * ((MediaArtwork.MAX_ARTWORK_BYTES + 2) / 3), base64.length)
        assertTrue(2 * json.size + MCP_OVERHEAD < TRANSPORT_LIMIT, "Two JSON copies of ${json.size} bytes plus overhead")
    }

    private companion object {
        /** The per-message limit of every Kora transport (Binder, wear frames, the MCP gate). */
        const val TRANSPORT_LIMIT = 256 * 1024

        /** Generous allowance for the JSON-RPC envelope, the content blocks and the other output fields. */
        const val MCP_OVERHEAD = 4 * 1024
    }
}
