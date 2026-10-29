package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.core.AuditOutcome
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** tools/call: results, errors, policy and conformance to the advertised schemas. */
class McpToolCallTest {
    private val access = TestAccess()
    private val audit = TestAudit()
    private val calls = mutableListOf<String>()
    private val node = KoraNode(
        nodeId = "sonora",
        policy = DefaultCallerPolicy(access),
        audit = audit,
    ).apply { provideEverything { calls += it } }

    private val allScopes = McpTools.all.map { it.operation.scope }.toSet()
    private val session = McpSession(node, AGENT, SERVER_INFO)

    private suspend fun initialize() {
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
    }

    private suspend fun call(name: String, arguments: String? = "{}"): JsonObject {
        val params = buildString {
            append("""{"name":${JsonPrimitive(name)}""")
            if (arguments != null) append(""","arguments":$arguments""")
            append("}")
        }
        return parse(session.handle(rpc("tools/call", parse(params))))
    }

    private fun JsonObject.text() = resultObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = resultObject["isError"]!!.jsonPrimitive.booleanOrNull!!

    @Test
    fun `a granted call returns structured content and the same JSON as text`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        val answer = call("media_library__list_playlists", """{"limit":2,"offset":1}""")

        assertFalse(answer.isError())
        val structured = answer.resultObject["structuredContent"]!!.jsonObject
        assertEquals(3, structured["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, structured["offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("pl-1", "pl-2"), structured["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("text", answer.resultObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(structured, Json.parseToJsonElement(answer.text()))
    }

    @Test
    fun `a shell session is authorized as the shell, never by the payload`() = runTest {
        val shell = Caller(CallerKind.SHELL, "shell")
        val shellSession = McpSession(node, shell, SERVER_INFO)
        shellSession.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25","clientInfo":{"name":"remote-agent","version":"1"}}""")))
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)
        val params = parse("""{"name":"media_library__list_playlists","arguments":{}}""")

        val refused = parse(shellSession.handle(rpc("tools/call", params)))
        access.grants[shell] = setOf(MediaLibrary.ReadScope)
        val allowed = parse(shellSession.handle(rpc("tools/call", params)))

        assertTrue(refused.isError())
        assertTrue(refused.text().startsWith("unauthorized"))
        assertFalse(allowed.isError())
        assertEquals(listOf(shell), audit.entries.map { it.caller }.distinct())
    }

    @Test
    fun `a command returns an empty object and runs the handler`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaPlayback.ControlScope)

        val answer = call("media_playback__pause")

        assertFalse(answer.isError())
        assertEquals(JsonObject(emptyMap()), answer.resultObject["structuredContent"])
        assertEquals(listOf("pause"), calls)
    }

    @Test
    fun `arguments may be omitted or null`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        assertFalse(call("media_library__list_playlists", null).isError())
        assertFalse(call("media_library__list_playlists", "null").isError())
    }

    @Test
    fun `a result that is not an object is wrapped under result`() = runTest {
        initialize()
        access.grants[AGENT] = allScopes

        val fix = call("location__get_last_fix").resultObject["structuredContent"]!!.jsonObject
        assertEquals(setOf("result"), fix.keys)
        assertEquals(43.26, fix["result"]!!.jsonObject["latitude"]!!.jsonPrimitive.content.toDouble())

        val none = KoraNode("none", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(eus.bihurri.kora.contract.fitness.FitnessSession) {
                on(eus.bihurri.kora.contract.fitness.FitnessSession.GetActiveSession) { null }
            }
        }
        val answer = parse(
            McpSession(none, AGENT, SERVER_INFO).apply {
                handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
            }.handle(rpc("tools/call", parse("""{"name":"fitness_session__get_active_session"}"""))),
        )
        assertEquals(parse("""{"result":null}"""), answer.resultObject["structuredContent"])
        assertEquals("""{"result":null}""", answer.text())
    }

    @Test
    fun `every tool can be called and its result conforms to the advertised schemas`() = runTest {
        initialize()
        access.grants[AGENT] = allScopes
        val arguments = mapOf(
            "media_library__list_playlists" to "{}",
            "media_library__get_playlist_tracks" to """{"playlistId":"pl-1","page":{"limit":5}}""",
            "media_library__search_tracks" to """{"query":"song"}""",
            "media_playback__play" to "{}",
            "media_playback__pause" to "{}",
            "media_playback__toggle" to "{}",
            "media_playback__next" to "{}",
            "media_playback__previous" to "{}",
            "media_playback__seek_to" to """{"positionMs":1500}""",
            "media_playback__play_playlist" to """{"playlistId":"pl-1","startIndex":1,"shuffle":true}""",
            "media_playback__play_tracks" to """{"trackIds":["t1","t2"]}""",
            "media_playback__get_state" to "{}",
            "media_history__list_plays" to """{"fromEpochMs":0,"toEpochMs":10000,"page":{"limit":5}}""",
            "media_artwork__get_artwork" to """{"artworkId":"art-0","maxEdgePx":64}""",
            "fitness_session__get_active_session" to "{}",
            "location__get_last_fix" to "{}",
            "location__configure_stream" to """{"desiredIntervalMs":1000,"maxLatencyMs":3000}""",
        )
        assertEquals(McpTools.all.map { it.name }.toSet(), arguments.keys)

        for (tool in McpTools.all) {
            val input = Json.parseToJsonElement(arguments.getValue(tool.name))
            assertEquals(emptyList<String>(), violations(tool.inputSchema, input), "input of ${tool.name}")

            val answer = call(tool.name, arguments.getValue(tool.name))
            assertFalse(answer.isError(), "${tool.name}: ${answer.resultObject["content"]}")
            val structured = answer.resultObject["structuredContent"]!!
            assertEquals(emptyList<String>(), violations(tool.outputSchema, structured), "output of ${tool.name}")
            val content = answer.resultObject["content"]!!.jsonArray.map { it.jsonObject }
            if (tool.operation == MediaArtwork.GetArtwork) {
                // An image block with the bytes of structuredContent, plus a metadata text (McpArtworkResultTest).
                assertEquals(listOf("image", "text"), content.map { it["type"]!!.jsonPrimitive.content }, tool.name)
                assertEquals(structured.jsonObject["bytes"], content[0]["data"], tool.name)
                assertEquals(structured.jsonObject["mimeType"], content[0]["mimeType"], tool.name)
            } else {
                assertEquals(structured, Json.parseToJsonElement(answer.text()), tool.name)
            }
        }
    }

    @Test
    fun `list_plays returns the plays that started inside the window in order`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaHistory.ReadScope)

        val answer = call("media_history__list_plays", """{"fromEpochMs":1000,"toEpochMs":4999}""")

        assertFalse(answer.isError(), answer.text())
        val structured = answer.resultObject["structuredContent"]!!.jsonObject
        assertEquals(1, structured["total"]!!.jsonPrimitive.content.toInt())
        val play = structured["items"]!!.jsonArray.single().jsonObject
        assertEquals("t1", play["track"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1000, play["startedAtEpochMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(900, play["playedMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("list_plays"), calls)
    }

    @Test
    fun `list_plays without its own grant is refused and an inverted window is invalid_payload`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        val refused = call("media_history__list_plays", """{"fromEpochMs":0,"toEpochMs":1}""")
        assertTrue(refused.isError())
        assertEquals("unauthorized: Scope 'media.history.read' is not granted", refused.text())

        access.grants[AGENT] = setOf(MediaHistory.ReadScope)
        val inverted = call("media_history__list_plays", """{"fromEpochMs":10,"toEpochMs":1}""")
        assertTrue(inverted.isError())
        assertTrue(inverted.text().startsWith("invalid_payload: "), inverted.text())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `playback state carries an enum value the schema allows`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)

        val state = call("media_playback__get_state").resultObject["structuredContent"]!!.jsonObject

        assertEquals("playing", state["status"]!!.jsonPrimitive.content)
    }

    // ---- policy --------------------------------------------------------------------------------------------------

    @Test
    fun `an agent without a grant gets a tool error`() = runTest {
        initialize()

        val answer = call("media_library__list_playlists")

        assertTrue(answer.isError())
        assertEquals("unauthorized: Scope 'media.library.read' is not granted", answer.text())
        assertFalse("structuredContent" in answer.resultObject)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the master switch off refuses a granted agent`() = runTest {
        initialize()
        access.grants[AGENT] = allScopes
        access.enabled = false

        val answer = call("media_playback__play")

        assertTrue(answer.isError())
        assertTrue(answer.text().startsWith("unauthorized:"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `scopes are explicit, read does not allow control`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaPlayback.ReadScope)

        assertFalse(call("media_playback__get_state").isError())
        val refused = call("media_playback__pause")
        assertTrue(refused.isError())
        assertEquals("unauthorized: Scope 'media.playback.control' is not granted", refused.text())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `calls are made and audited as the session agent`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        call("media_library__list_playlists", """{"caller":"SELF","id":"sonora","kind":"SELF"}""")
        call("media_playback__play")

        assertEquals(listOf(AGENT, AGENT), audit.entries.map { it.caller })
        assertEquals(listOf(AuditOutcome.Ok, AuditOutcome.Failed(ErrorCode.UNAUTHORIZED)), audit.entries.map { it.outcome })
    }

    @Test
    fun `identity in the metadata of a modern request is not trusted either`() = runTest {
        val params = withMeta("""{"name":"media_library__list_playlists","arguments":{}}""")
        val answer = parse(session.handle(rpc("tools/call", params)))

        assertTrue(answer.isError())
        assertEquals(AGENT, audit.entries.single().caller)
        assertEquals("complete", answer.resultObject["resultType"]!!.jsonPrimitive.content)
    }

    // ---- errors --------------------------------------------------------------------------------------------------

    @Test
    fun `an unknown tool is invalid params`() = runTest {
        initialize()

        val answer = call("media_library__nope")

        assertEquals(-32602, answer.errorCode)
        assertEquals("Unknown tool: media_library__nope", answer["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(-32602, call("media.library.list_playlists").errorCode)
    }

    @Test
    fun `a malformed call is invalid params`() = runTest {
        initialize()
        val bad = listOf(
            """{}""",
            """{"name":3}""",
            """{"name":null}""",
            """{"name":"media_library__list_playlists","arguments":[1]}""",
            """{"name":"media_library__list_playlists","arguments":"x"}""",
        )
        for (params in bad) {
            assertEquals(-32602, parse(session.handle(rpc("tools/call", parse(params)))).errorCode, params)
        }
        assertEquals(-32602, parse(session.handle(rpc("tools/call"))).errorCode)
    }

    @Test
    fun `bad arguments are a tool error with invalid_payload`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        for (arguments in listOf("""{"limit":"ten"}""", """{"limit":0}""", """{"offset":-1}""", """{"limit":1.5}""")) {
            val answer = call("media_library__list_playlists", arguments)
            assertTrue(answer.isError(), arguments)
            assertTrue(answer.text().startsWith("invalid_payload: "), answer.text())
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `missing required arguments are a tool error`() = runTest {
        initialize()
        access.grants[AGENT] = setOf(MediaLibrary.ReadScope)

        val answer = call("media_library__search_tracks", "{}")

        assertTrue(answer.isError())
        assertTrue(answer.text().startsWith("invalid_payload: "))
    }

    @Test
    fun `an unauthorized agent cannot probe arguments`() = runTest {
        initialize()

        val answer = call("media_library__list_playlists", """{"limit":"ten"}""")

        assertEquals("unauthorized: Scope 'media.library.read' is not granted", answer.text())
    }

    @Test
    fun `a capability nobody provides is provider_unavailable`() = runTest {
        val partial = KoraNode("partial", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { eus.bihurri.kora.contract.media.Page(emptyList(), 0) } }
        }
        val session = McpSession(partial, AGENT, SERVER_INFO)
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        access.grants[AGENT] = allScopes

        val answer = parse(session.handle(rpc("tools/call", parse("""{"name":"media_playback__pause"}"""))))

        assertTrue(answer.isError())
        assertEquals("provider_unavailable: No provider offers 'media.playback'", answer.text())
    }

    @Test
    fun `a status this build reads as unknown still conforms to the output schema`() = runTest {
        // What a peer on a newer minor produces once decoded here: its new constant became UNKNOWN.
        val relaying = KoraNode("relaying", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.GetState) { PlaybackState(PlaybackStatus.UNKNOWN) } }
        }
        val session = McpSession(relaying, AGENT, SERVER_INFO)
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        access.grants[AGENT] = allScopes

        val answer = parse(session.handle(rpc("tools/call", parse("""{"name":"media_playback__get_state"}"""))))

        assertFalse(answer.isError())
        val structured = answer.resultObject["structuredContent"]!!.jsonObject
        assertEquals("unknown", structured["status"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), violations(McpTools.find("media_playback__get_state")!!.outputSchema, structured))
    }

    @Test
    fun `a handler failure is reported without details`() = runTest {
        val failing = KoraNode("failing", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { error("secret stack detail") } }
        }
        val session = McpSession(failing, AGENT, SERVER_INFO)
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        access.grants[AGENT] = allScopes

        val answer = parse(session.handle(rpc("tools/call", parse("""{"name":"media_playback__play"}"""))))

        assertTrue(answer.isError())
        assertEquals("internal: Internal error", answer.text())
    }

    @Test
    fun `a slow handler times out as a tool error`() = runTest {
        val slow = KoraNode("slow", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaPlayback) { on(MediaPlayback.Play) { kotlinx.coroutines.awaitCancellation() } }
        }
        val gate = eus.bihurri.kora.core.JsonGate(slow, timeout = kotlin.time.Duration.parse("1s"), forwardToPeers = true)
        val session = McpSession(slow, AGENT, SERVER_INFO, gate)
        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        access.grants[AGENT] = allScopes

        val answer = parse(session.handle(rpc("tools/call", parse("""{"name":"media_playback__play"}"""))))

        assertTrue(answer.isError())
        assertTrue(answer.text().startsWith("timeout: "))
    }
}
