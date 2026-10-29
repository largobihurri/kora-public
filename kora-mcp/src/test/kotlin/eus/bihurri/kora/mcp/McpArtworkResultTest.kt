package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** tools/call of `media_artwork__get_artwork`: an image content block, metadata text, and the answer size. */
class McpArtworkResultTest {
    private val access = TestAccess().apply { grants[AGENT] = setOf(MediaArtwork.ReadScope, MediaPlayback.ReadScope) }

    private fun sessionServing(image: ArtworkImage): McpSession {
        val node = KoraNode("sonora", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaArtwork) { on(MediaArtwork.GetArtwork) { image } }
        }
        return McpSession(node, AGENT, SERVER_INFO)
    }

    /** The JSON-RPC answer text to a legacy-era `get_artwork` call. */
    private suspend fun McpSession.artworkAnswer(): String {
        handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        return checkNotNull(handle(rpc("tools/call", parse(ARTWORK_CALL))))
    }

    private fun JsonObject.content() = resultObject["content"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.structured() = resultObject["structuredContent"]!!.jsonObject

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun maxSize(mimeType: ArtworkMimeType) = ArtworkImage(
        ByteArray(MediaArtwork.MAX_ARTWORK_BYTES) { (it * 31 + 7).toByte() },
        mimeType,
        widthPx = 1024,
        heightPx = 1024,
    )

    private fun occurrences(text: String, part: String): Int = text.split(part).size - 1

    @Test
    fun `an artwork result is an image block with the bytes and the mime type`() = runTest {
        val answer = parse(sessionServing(artworkImage).artworkAnswer())

        assertFalse(answer.resultObject["isError"]!!.jsonPrimitive.booleanOrNull!!)
        val content = answer.content()
        assertEquals(listOf("image", "text"), content.map { it["type"]!!.jsonPrimitive.content })
        val image = content[0]
        assertEquals(setOf("type", "data", "mimeType"), image.keys)
        assertEquals(base64(artworkImage.bytes), image["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the text block carries metadata only, never the bytes`() = runTest {
        val image = maxSize(ArtworkMimeType.JPEG)
        val answer = parse(sessionServing(image).artworkAnswer())

        val text = answer.content()[1]
        assertEquals(setOf("type", "text"), text.keys)
        val metadata = text["text"]!!.jsonPrimitive.content
        assertTrue(metadata.length < 200, metadata)
        assertFalse(base64(image.bytes).take(32) in metadata, metadata)
        for (fact in listOf("image/jpeg", "1024x1024", "${MediaArtwork.MAX_ARTWORK_BYTES} bytes")) {
            assertTrue(fact in metadata, "'$fact' in '$metadata'")
        }
    }

    @Test
    fun `structured content is the full output and conforms to the output schema`() = runTest {
        val answer = parse(sessionServing(artworkImage).artworkAnswer())

        val tool = McpTools.nameOf(MediaArtwork.GetArtwork).let { McpTools.find(it)!! }
        val structured = answer.structured()
        assertEquals(emptyList<String>(), violations(tool.outputSchema, structured))
        assertEquals(Json.encodeToJsonElement(ArtworkImage.serializer(), artworkImage), structured)
        assertEquals(structured["bytes"], answer.content()[0]["data"])
        assertEquals(structured["mimeType"], answer.content()[0]["mimeType"])
    }

    @Test
    fun `a stateless-era call gets the image block too`() = runTest {
        val session = sessionServing(artworkImage)

        val answer = parse(session.handle(rpc("tools/call", withMeta(ARTWORK_CALL))))

        assertEquals("complete", answer.resultObject["resultType"]!!.jsonPrimitive.content)
        assertEquals(listOf("image", "text"), answer.content().map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `an unknown mime type is no image block but the plain JSON text`() = runTest {
        val unknown = ArtworkImage(byteArrayOf(9, 8, 7), ArtworkMimeType.UNKNOWN, widthPx = 3, heightPx = 1)

        val answer = parse(sessionServing(unknown).artworkAnswer())

        assertFalse(answer.resultObject["isError"]!!.jsonPrimitive.booleanOrNull!!)
        val block = answer.content().single()
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals(answer.structured(), Json.parseToJsonElement(block["text"]!!.jsonPrimitive.content))
        assertEquals("unknown", answer.structured()["mimeType"]!!.jsonPrimitive.content)
        val tool = McpTools.find(McpTools.nameOf(MediaArtwork.GetArtwork))!!
        assertEquals(emptyList<String>(), violations(tool.outputSchema, answer.structured()))
    }

    @Test
    fun `a max-size artwork answer holds the bytes twice and fits the 256 KiB gate limit`() = runTest {
        for (mimeType in listOf(ArtworkMimeType.JPEG, ArtworkMimeType.UNKNOWN)) {
            val image = maxSize(mimeType)
            val legacy = sessionServing(image).artworkAnswer()
            val modern = checkNotNull(sessionServing(image).handle(rpc("tools/call", withMeta(ARTWORK_CALL))))

            for (answer in listOf(legacy, modern)) {
                val size = answer.encodeToByteArray().size
                assertTrue(size < GATE_ANSWER_LIMIT, "$mimeType: $size bytes")
                assertEquals(2, occurrences(answer, base64(image.bytes)), "$mimeType: Base64 copies")
            }
        }
    }

    @Test
    fun `other tools keep the single text block byte for byte`() = runTest {
        val node = KoraNode("sonora", policy = DefaultCallerPolicy(access)).apply { provideEverything() }
        val session = McpSession(node, AGENT, SERVER_INFO)
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))

        val answer = session.handle(rpc("tools/call", parse("""{"name":"media_playback__get_state"}""")))

        assertEquals(
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"status\":\"playing\",""" +
                """\"positionMs\":1000,\"updatedAtEpochMs\":2,\"track\":{\"id\":\"t1\",\"title\":\"Song\",""" +
                """\"artist\":\"Artist\",\"album\":null,\"durationMs\":1000,\"artworkId\":null},""" +
                """\"shuffle\":true}"}],"structuredContent":{"status":"playing","positionMs":1000,""" +
                """"updatedAtEpochMs":2,"track":{"id":"t1","title":"Song","artist":"Artist","album":null,""" +
                """"durationMs":1000,"artworkId":null},"shuffle":true},"isError":false}}""",
            answer,
        )
    }

    private companion object {
        /** `McpGate` refuses answers above `PayloadLimits.MAX_ENVELOPE_BYTES` (kora-android): 256 KiB. */
        const val GATE_ANSWER_LIMIT = 256 * 1024

        const val ARTWORK_CALL = """{"name":"media_artwork__get_artwork","arguments":{"artworkId":"art-0"}}"""
    }
}
