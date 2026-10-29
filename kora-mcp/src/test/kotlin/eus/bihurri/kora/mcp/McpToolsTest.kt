package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Capabilities
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun tool(name: String): McpTool = McpTools.find(name) ?: error("No tool $name")

    private fun JsonObject.properties() = this["properties"]!!.jsonObject

    private fun JsonObject.required() = this["required"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `there is one tool per operation of every built-in capability`() {
        val operations = Capabilities.all.flatMap { it.operations }
        assertEquals(operations.size, McpTools.all.size)
        assertEquals(operations.size, McpTools.all.map { it.name }.toSet().size)
        assertEquals(operations.map { McpTools.nameOf(it) }, McpTools.all.map { it.name })
    }

    @Test
    fun `names follow the pattern and the MCP name rule`() {
        val mcpRule = Regex("^[a-zA-Z0-9_-]{1,64}$")
        for (tool in McpTools.all) {
            assertTrue(mcpRule.matches(tool.name), tool.name)
        }
        assertEquals("media_library__list_playlists", McpTools.nameOf(MediaLibrary.ListPlaylists))
        assertEquals("media_playback__seek_to", McpTools.nameOf(MediaPlayback.SeekTo))
        assertEquals("media_history__list_plays", McpTools.nameOf(MediaHistory.ListPlays))
        assertEquals("media_artwork__get_artwork", McpTools.nameOf(MediaArtwork.GetArtwork))
        assertTrue(McpTools.all.any { it.name == "fitness_session__get_active_session" })
        assertTrue(McpTools.all.any { it.name == "location__get_last_fix" })
    }

    @Test
    fun `a name maps back to its capability and operation`() {
        for (tool in McpTools.all) {
            assertSame(tool, McpTools.find(McpTools.nameOf(tool.operation)))
            assertSame(tool.operation, tool.capability.findOperation(tool.operation.name))
            assertEquals(tool.capability.id, tool.operation.capabilityId)
        }
        assertNull(McpTools.find("media_library__nope"))
        assertNull(McpTools.find("media.library.list_playlists"))
        assertNull(McpTools.find(""))
    }

    @Test
    fun `the description carries the required scope`() {
        assertEquals(
            "List the user's playlists, one page at a time. Requires scope media.library.read.",
            tool("media_library__list_playlists").description,
        )
        assertTrue(tool("media_playback__pause").description.endsWith("Requires scope media.playback.control."))
        assertEquals("List playlists", tool("media_library__list_playlists").title)
    }

    @Test
    fun `every input is a JSON object schema`() {
        for (tool in McpTools.all) {
            assertEquals("object", tool.inputSchema["type"]!!.jsonPrimitive.content, tool.name)
            assertEquals("object", tool.outputSchema["type"]!!.jsonPrimitive.content, tool.name)
        }
    }

    @Test
    fun `list playlists input has optional paging and output requires the whole page`() {
        val list = tool("media_library__list_playlists")

        assertEquals(listOf("limit", "offset"), list.inputSchema.properties().keys.toList())
        assertFalse("required" in list.inputSchema)
        assertEquals(json("""{"type":"integer"}"""), list.inputSchema.properties()["limit"])
        assertFalse(list.wrapsOutput)
        assertEquals(listOf("items", "total", "offset"), list.outputSchema.required())
        val item = list.outputSchema.properties()["items"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(listOf("id", "name", "trackCount", "artworkId"), item.required())
        assertEquals(json("""{"type":["string","null"]}"""), item.properties()["artworkId"])
    }

    @Test
    fun `get artwork takes an id and an optional edge and returns Base64 bytes`() {
        val artwork = tool("media_artwork__get_artwork")

        assertEquals(listOf("artworkId", "maxEdgePx"), artwork.inputSchema.properties().keys.toList())
        assertEquals(listOf("artworkId"), artwork.inputSchema.required())
        assertEquals(json("""{"type":"integer","minimum":16,"maximum":1024}"""), artwork.inputSchema.properties()["maxEdgePx"])
        assertFalse(artwork.wrapsOutput)
        assertEquals(json("""{"type":"string","contentEncoding":"base64"}"""), artwork.outputSchema.properties()["bytes"])
        assertTrue(artwork.description.endsWith("Requires scope media.artwork.read."))
    }

    @Test
    fun `nested requests keep their structure`() {
        val tracks = tool("media_library__get_playlist_tracks")

        assertEquals(listOf("playlistId"), tracks.inputSchema.required())
        assertEquals(listOf("limit", "offset"), tracks.inputSchema.properties()["page"]!!.jsonObject.properties().keys.toList())
        val play = tool("media_playback__play_tracks")
        assertEquals(listOf("trackIds"), play.inputSchema.required())
        assertEquals(json("""{"type":"array","items":{"type":"string"}}"""), play.inputSchema.properties()["trackIds"])
    }

    @Test
    fun `commands take no arguments and return an empty object`() {
        val closed = json("""{"type":"object","additionalProperties":false}""")
        val pause = tool("media_playback__pause")

        assertEquals(closed, pause.inputSchema)
        assertEquals(closed, pause.outputSchema)
        assertFalse(pause.wrapsOutput)
        assertEquals(closed, tool("media_playback__get_state").inputSchema)
    }

    @Test
    fun `playback status in a result admits the unknown fallback last`() {
        val status = tool("media_playback__get_state").outputSchema.properties()["status"]!!.jsonObject

        assertEquals(
            json("""["idle","buffering","playing","paused","ended","unknown"]"""),
            status["enum"],
        )
        assertEquals(
            json("""["image/jpeg","image/png","image/webp","unknown"]"""),
            tool("media_artwork__get_artwork").outputSchema.properties()["mimeType"]!!.jsonObject["enum"],
        )
    }

    @Test
    fun `input schemas never admit the fallback and output schemas differ only by it`() {
        val changed = mutableListOf<String>()
        for (tool in McpTools.all) {
            assertEquals(JsonSchemas.of(tool.operation.inputSerializer.descriptor), tool.inputSchema, tool.name)
            assertFalse("\"unknown\"" in tool.inputSchema.toString(), tool.name)

            val writerOnly = JsonSchemas.of(tool.operation.outputSerializer.descriptor, requireAll = true)
            val expected = if (tool.wrapsOutput) wrapped(writerOnly) else writerOnly
            assertEquals(expected, withoutFallbacks(tool.outputSchema), tool.name)
            if (expected != tool.outputSchema) changed += tool.name
        }
        assertEquals(
            listOf("media_playback__get_state", "media_artwork__get_artwork", "fitness_session__get_active_session"),
            changed,
        )
    }

    /** [schema] with the trailing `unknown` fallback removed from every `enum`, i.e. the writer-only form. */
    private fun withoutFallbacks(schema: JsonElement): JsonElement = when (schema) {
        is JsonObject -> JsonObject(
            schema.mapValues { (key, value) ->
                if (key == "enum" && value.jsonArray.last() == JsonPrimitive("unknown")) {
                    JsonArray(value.jsonArray.dropLast(1))
                } else {
                    withoutFallbacks(value)
                }
            },
        )
        is JsonArray -> JsonArray(schema.map { withoutFallbacks(it) })
        else -> schema
    }

    private fun wrapped(result: JsonObject): JsonObject =
        json("""{"type":"object","properties":{"result":$result},"required":["result"]}""").jsonObject

    @Test
    fun `no input schema advertises the unknown fallback`() {
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element["enum"]?.let { values ->
                    assertFalse(values.jsonArray.any { it.jsonPrimitive.content == "unknown" }, element.toString())
                }.also { element.values.forEach(::walk) }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        McpTools.all.forEach { walk(it.inputSchema) }
    }

    @Test
    fun `only the artwork edge is a bounded integer`() {
        val bounded = mutableListOf<String>()
        fun walk(path: String, element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if ("minimum" in element || "maximum" in element) bounded += path
                    element.forEach { (key, value) -> walk("$path/$key", value) }
                }
                is JsonArray -> element.forEachIndexed { i, value -> walk("$path/$i", value) }
                else -> Unit
            }
        }
        McpTools.all.forEach { walk(it.name, it.toJson()) }
        assertEquals(listOf("media_artwork__get_artwork/inputSchema/properties/maxEdgePx"), bounded)
    }

    @Test
    fun `results that are not objects are wrapped under result`() {
        val wrapped = McpTools.all.filter { it.wrapsOutput }.map { it.name }
        assertEquals(listOf("fitness_session__get_active_session", "location__get_last_fix"), wrapped)

        val fix = tool("location__get_last_fix")
        assertEquals(listOf("result"), fix.outputSchema.required())
        val result = fix.outputSchema.properties()["result"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(json("""{"type":"null"}"""), result[1])
        assertEquals(
            listOf("latitude", "longitude", "altitudeMeters", "horizontalAccuracyMeters", "timeEpochMs"),
            result[0].jsonObject.required(),
        )
    }

    @Test
    fun `the listed form carries name title description and both schemas`() {
        val listed = tool("media_library__search_tracks").toJson()

        assertEquals(setOf("name", "title", "description", "inputSchema", "outputSchema"), listed.keys)
        assertEquals(listOf("query"), listed["inputSchema"]!!.jsonObject.required())
    }
}
