package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.Capabilities
import eus.bihurri.kora.contract.OperationEffect
import eus.bihurri.kora.contract.StreamDirection
import eus.bihurri.kora.contract.ai.AiCapability
import eus.bihurri.kora.contract.files.FilesCapability
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.MediaVolume
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
    fun `there is one tool per operation without a byte stream of every built-in capability`() {
        val operations = Capabilities.all.flatMap { it.operations }.filter { it.stream == StreamDirection.NONE }
        assertEquals(operations.size, McpTools.all.size)
        assertEquals(operations.size, McpTools.all.map { it.name }.toSet().size)
        assertEquals(operations.map { McpTools.nameOf(it) }, McpTools.all.map { it.name })
    }

    @Test
    fun `an operation that carries a byte stream is not a tool, and discard of files is one`() {
        assertNull(McpTools.find("files__upload"))
        assertNull(McpTools.find("files__download"))
        assertEquals(listOf("files__discard"), McpTools.all.filter { it.capability == FilesCapability }.map { it.name })
        assertSame(FilesCapability.Discard, tool("files__discard").operation)
        assertTrue(McpTools.all.all { it.operation.stream == StreamDirection.NONE })
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
        assertEquals("media_volume__get_volume", McpTools.nameOf(MediaVolume.GetVolume))
        assertEquals("media_volume__set_volume", McpTools.nameOf(MediaVolume.SetVolume))
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
    fun `volume tools read with the read scope and set an absolute level with an optional write order`() {
        val get = tool("media_volume__get_volume")
        assertTrue(get.description.endsWith("Requires scope media.volume.read."), get.description)
        assertEquals(json("""{"type":"object","additionalProperties":false}"""), get.inputSchema)
        assertEquals(listOf("level", "maxLevel", "muted"), get.outputSchema.required())

        val set = tool("media_volume__set_volume")
        assertTrue(set.description.endsWith("Requires scope media.volume.control."), set.description)
        assertEquals(listOf("level", "muted", "order"), set.inputSchema.properties().keys.toList())
        assertEquals(listOf("level"), set.inputSchema.required())
        // IntegerRange needs a maximum and the level has none (the provider clamps), so the schema is a plain integer.
        assertEquals(json("""{"type":"integer"}"""), set.inputSchema.properties()["level"])
        assertEquals(json("""{"type":["boolean","null"]}"""), set.inputSchema.properties()["muted"])
        val order = set.inputSchema.properties()["order"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(json("""{"type":"null"}"""), order[1])
        assertEquals(listOf("writer", "sequence"), order[0].jsonObject.properties().keys.toList())
        assertEquals(listOf("writer", "sequence"), order[0].jsonObject.required())
        assertFalse(set.wrapsOutput)
        assertEquals(listOf("level", "maxLevel", "muted"), set.outputSchema.required())
    }

    @Test
    fun `ai tools start and cancel jobs with the run scope and read engines and jobs with the read scope`() {
        assertEquals("ai__summarize_meeting", McpTools.nameOf(AiCapability.SummarizeMeeting))
        val summarize = tool("ai__summarize_meeting")
        assertTrue(summarize.description.endsWith("Requires scope ai.run."), summarize.description)
        assertEquals(
            listOf("transcript", "engineId", "title", "startedAtEpochMs", "durationMs", "language", "requestKey"),
            summarize.inputSchema.properties().keys.toList(),
        )
        assertEquals(listOf("transcript"), summarize.inputSchema.required())
        assertFalse(summarize.wrapsOutput)
        assertEquals(json("""{"readOnlyHint":false,"destructiveHint":false}"""), summarize.toJson()["annotations"])

        val ask = tool("ai__ask")
        assertEquals(listOf("question", "documents"), ask.inputSchema.required())
        assertTrue(tool("ai__cancel_job").description.endsWith("Requires scope ai.run."))
        val transcribe = tool("ai__transcribe")
        assertTrue(transcribe.description.endsWith("Requires scope ai.run."), transcribe.description)
        assertEquals(listOf("audioFileId"), transcribe.inputSchema.required())

        for (name in listOf("ai__list_engines", "ai__get_job", "ai__get_transcript_passages")) {
            assertTrue(tool(name).description.endsWith("Requires scope ai.read."), name)
            assertEquals(json("""{"readOnlyHint":true,"destructiveHint":false}"""), tool(name).toJson()["annotations"], name)
        }
        assertEquals(listOf("jobId"), tool("ai__get_job").inputSchema.required())
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
            listOf(
                "media_playback__get_state", "media_artwork__get_artwork", "fitness_session__get_active_session",
                "reminders__get_overview", "reminders__list_reminders", "reminders__get_reminder",
                "reminders__create_reminder", "reminders__update_reminder", "reminders__complete_reminder",
                "reminders__reopen_reminder", "reminders__snooze_reminder", "reminders__create_list",
                "reminders__update_list", "calendar__get_event", "calendar__create_event", "calendar__update_event",
                "notes__get_overview", "notes__update_settings",
                "ai__list_engines", "ai__transcribe", "ai__summarize_meeting", "ai__ask", "ai__get_job",
                "ai__cancel_job",
            ),
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
    fun `only the artwork edge, the windows of organizer listings, the part of a note and ai offsets, durations, counts and progress are bounded integers`() {
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
        assertEquals(
            listOf(
                "media_artwork__get_artwork/inputSchema/properties/maxEdgePx",
                "reminders__get_overview/inputSchema/properties/limit",
                "reminders__get_overview/inputSchema/properties/offset",
                "reminders__list_reminders/inputSchema/properties/limit",
                "reminders__list_reminders/inputSchema/properties/offset",
                "calendar__get_overview/inputSchema/properties/limit",
                "calendar__get_overview/inputSchema/properties/offset",
                "calendar__list_events/inputSchema/properties/limit",
                "calendar__list_events/inputSchema/properties/offset",
                "notes__get_overview/inputSchema/properties/limit",
                "notes__get_overview/inputSchema/properties/offset",
                "notes__list_notes/inputSchema/properties/limit",
                "notes__list_notes/inputSchema/properties/offset",
                "notes__get_note/inputSchema/properties/offset",
                "notes__get_note/inputSchema/properties/maxChars",
                "ai__transcribe/inputSchema/properties/fromMs",
                "ai__transcribe/outputSchema/properties/progressPercent",
                "ai__transcribe/outputSchema/properties/transcript/anyOf/0/properties/passageCount",
                "ai__summarize_meeting/inputSchema/properties/durationMs",
                "ai__summarize_meeting/outputSchema/properties/progressPercent",
                "ai__summarize_meeting/outputSchema/properties/transcript/anyOf/0/properties/passageCount",
                "ai__ask/outputSchema/properties/progressPercent",
                "ai__ask/outputSchema/properties/transcript/anyOf/0/properties/passageCount",
                "ai__get_job/outputSchema/properties/progressPercent",
                "ai__get_job/outputSchema/properties/transcript/anyOf/0/properties/passageCount",
                "ai__get_transcript_passages/inputSchema/properties/offset",
                "ai__get_transcript_passages/inputSchema/properties/limit",
                "ai__get_transcript_passages/outputSchema/properties/passages/items/properties/startMs",
                "ai__get_transcript_passages/outputSchema/properties/passages/items/properties/endMs",
                "ai__get_transcript_passages/outputSchema/properties/total",
                "ai__get_transcript_passages/outputSchema/properties/offset",
                "ai__cancel_job/outputSchema/properties/progressPercent",
                "ai__cancel_job/outputSchema/properties/transcript/anyOf/0/properties/passageCount",
            ),
            bounded,
        )
    }

    @Test
    fun `the annotations publish the effect of the operation as readOnlyHint and destructiveHint`() {
        for (tool in McpTools.all) {
            val expected = when (tool.operation.effect) {
                OperationEffect.READ -> """{"readOnlyHint":true,"destructiveHint":false}"""
                OperationEffect.WRITE -> """{"readOnlyHint":false,"destructiveHint":false}"""
                OperationEffect.DESTRUCTIVE -> """{"readOnlyHint":false,"destructiveHint":true}"""
            }
            assertEquals(json(expected), tool.toJson()["annotations"], tool.name)
        }
        assertEquals(json("""{"readOnlyHint":true,"destructiveHint":false}"""), tool("media_library__list_playlists").toJson()["annotations"])
        assertEquals(json("""{"readOnlyHint":false,"destructiveHint":false}"""), tool("notes__delete_note").toJson()["annotations"])
        assertEquals(json("""{"readOnlyHint":false,"destructiveHint":true}"""), tool("notes__delete_note_permanently").toJson()["annotations"])
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
    fun `the listed form carries name title description both schemas and the annotations`() {
        val listed = tool("media_library__search_tracks").toJson()

        assertEquals(setOf("name", "title", "description", "inputSchema", "outputSchema", "annotations"), listed.keys)
        assertEquals(listOf("query"), listed["inputSchema"]!!.jsonObject.required())
    }
}
