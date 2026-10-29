package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.testing.provideStubbed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JSON-RPC framing, the two lifecycles, ping and tools/list. */
class McpSessionTest {
    private val access = TestAccess()
    private val node = KoraNode("bridge").apply {
        provideStubbed(MediaLibrary) { on(MediaLibrary.ListPlaylists) { eus.bihurri.kora.contract.media.Page(emptyList(), 0) } }
    }

    private fun session(pageSize: Int = McpSession.DEFAULT_PAGE_SIZE, target: KoraNode = node) =
        McpSession(target, AGENT, SERVER_INFO, pageSize = pageSize)

    private suspend fun McpSession.initialized(version: String = "2025-11-25"): McpSession {
        handle(rpc("initialize", parse("""{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"c","version":"1"}}""")))
        assertNull(handle(rpc("notifications/initialized", id = null)))
        return this
    }

    private fun toolNames(answer: JsonObject) = answer.resultObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    // ---- framing -------------------------------------------------------------------------------------------------

    @Test
    fun `malformed JSON is a parse error with a null id`() = runTest {
        for (bad in listOf("", "{", "not json", """{"jsonrpc":"2.0",""", "{} trailing")) {
            val answer = parse(session().handle(bad))
            assertEquals(-32700, answer.errorCode, bad)
            assertEquals(JsonNull, answer["id"], bad)
        }
    }

    @Test
    fun `things that are not a request are invalid requests`() = runTest {
        val session = session()
        assertEquals(-32600, parse(session.handle("[]")).errorCode)
        assertEquals(-32600, parse(session.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")).errorCode)
        assertEquals(-32600, parse(session.handle("42")).errorCode)
        assertEquals(-32600, parse(session.handle("""{"jsonrpc":"2.0","id":1}""")).errorCode)
        assertEquals(-32600, parse(session.handle("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")).errorCode)
        assertEquals(-32600, parse(session.handle("""{"id":1,"method":"ping"}""")).errorCode)
    }

    @Test
    fun `an id must be a string or an integer`() = runTest {
        val session = session()
        for (id in listOf("null", "1.5", "true", "[1]", "{}")) {
            val answer = parse(session.handle("""{"jsonrpc":"2.0","id":$id,"method":"ping"}"""))
            assertEquals(-32600, answer.errorCode, id)
            assertEquals(JsonNull, answer["id"], id)
        }
    }

    @Test
    fun `the id is echoed as sent`() = runTest {
        val session = session()
        assertEquals(JsonPrimitive("abc"), parse(session.handle(rpc("ping", id = JsonPrimitive("abc"))))["id"])
        assertEquals(JsonPrimitive(7), parse(session.handle(rpc("ping", id = JsonPrimitive(7))))["id"])
    }

    @Test
    fun `unknown methods are method not found`() = runTest {
        val session = session()
        for (method in listOf("nope", "resources/list", "prompts/list", "logging/setLevel", "tools/delete")) {
            assertEquals(-32601, parse(session.handle(rpc(method))).errorCode, method)
        }
    }

    @Test
    fun `params must be an object`() = runTest {
        assertEquals(-32602, parse(session().handle("""{"jsonrpc":"2.0","id":1,"method":"ping","params":[1]}""")).errorCode)
    }

    @Test
    fun `notifications and responses never get an answer`() = runTest {
        val session = session()
        assertNull(session.handle(rpc("notifications/initialized", id = null)))
        assertNull(session.handle(rpc("notifications/cancelled", parse("""{"requestId":1}"""), id = null)))
        assertNull(session.handle(rpc("unknown/notification", id = null)))
        assertNull(session.handle(rpc("tools/call", parse("""{"name":"x"}"""), id = null)))
        assertNull(session.handle("""{"jsonrpc":"2.0","method":"ping"}"""))
        assertNull(session.handle("""{"method":"ping"}"""))
        assertNull(session.handle("""{"jsonrpc":"2.0","id":9,"result":{}}"""))
        assertNull(session.handle("""{"jsonrpc":"2.0","id":9,"error":{"code":-1,"message":"x"}}"""))
    }

    @Test
    fun `answers are single line JSON for stdio`() = runTest {
        val text = session().initialized().handle(rpc("tools/list"))!!
        assertFalse('\n' in text)
        assertEquals("2.0", Json.parseToJsonElement(text).jsonObject["jsonrpc"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ping answers an empty result at any time`() = runTest {
        val session = session()
        assertEquals(JsonObject(emptyMap()), parse(session.handle(rpc("ping"))).resultObject)
        session.initialized()
        assertEquals(JsonObject(emptyMap()), parse(session.handle(rpc("ping"))).resultObject)
    }

    @Test
    fun `a session only acts for external callers`() {
        for (kind in listOf(CallerKind.SELF, CallerKind.PEER)) {
            assertFalse(McpSession.serves(kind))
            assertThrows(IllegalArgumentException::class.java) { McpSession(node, Caller(kind, "x"), SERVER_INFO) }
        }
        for (kind in listOf(CallerKind.REMOTE_AGENT, CallerKind.SHELL, CallerKind.EXTERNAL_APP)) {
            assertTrue(McpSession.serves(kind))
            McpSession(node, Caller(kind, "x"), SERVER_INFO)
        }
        assertThrows(IllegalArgumentException::class.java) { McpSession(node, AGENT, SERVER_INFO, pageSize = 0) }
    }

    @Test
    fun `a request before the handshake tells the client to initialize first`() = runTest {
        // Transports that restart the session's process (the adb bridge) recognise this wording to replay the handshake.
        val answer = parse(session().handle(rpc("tools/list")))

        assertEquals(-32602, answer.errorCode)
        assertTrue((answer["error"] as JsonObject)["message"]!!.jsonPrimitive.content.contains("initialize first"))
    }

    // ---- legacy handshake -------------------------------------------------------------------------------------

    @Test
    fun `initialize answers the requested version when it is supported`() = runTest {
        for (version in McpProtocol.LEGACY_VERSIONS) {
            val answer = parse(session().handle(rpc("initialize", parse("""{"protocolVersion":"$version","capabilities":{}}"""))))
            assertEquals(version, answer.resultObject["protocolVersion"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `initialize answers the newest handshake version for anything else`() = runTest {
        for (version in listOf("2024-11-05", "2025-03-26", "1.0.0", "2026-07-28", "")) {
            val answer = parse(session().handle(rpc("initialize", parse("""{"protocolVersion":"$version","capabilities":{}}"""))))
            assertEquals("2025-11-25", answer.resultObject["protocolVersion"]!!.jsonPrimitive.content, version)
        }
    }

    @Test
    fun `initialize reports capabilities identity and instructions`() = runTest {
        val result = parse(session().handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))).resultObject

        assertEquals(parse("""{"tools":{"listChanged":true}}"""), result["capabilities"])
        assertEquals(parse("""{"name":"kora-test","title":"Kora test","version":"1.2.3"}"""), result["serverInfo"])
        assertEquals("Use the tools.", result["instructions"]!!.jsonPrimitive.content)
        assertFalse("resultType" in result)
    }

    @Test
    fun `initialize needs a protocol version`() = runTest {
        assertEquals(-32602, parse(session().handle(rpc("initialize", parse("{}")))).errorCode)
        assertEquals(-32602, parse(session().handle(rpc("initialize", parse("""{"protocolVersion":3}""")))).errorCode)
    }

    @Test
    fun `tools are refused until initialize has run`() = runTest {
        val session = session()
        assertEquals(-32602, parse(session.handle(rpc("tools/list"))).errorCode)
        assertEquals(-32602, parse(session.handle(rpc("tools/call", parse("""{"name":"media_library__list_playlists"}""")))).errorCode)

        session.handle(rpc("initialize", parse("""{"protocolVersion":"2025-11-25"}""")))
        assertTrue(toolNames(parse(session.handle(rpc("tools/list")))).isNotEmpty())
    }

    @Test
    fun `a legacy result carries no resultType`() = runTest {
        val result = parse(session().initialized().handle(rpc("tools/list"))).resultObject
        assertFalse("resultType" in result)
        assertFalse("ttlMs" in result)
        assertFalse("_meta" in result)
    }

    // ---- modern era ---------------------------------------------------------------------------------------------

    @Test
    fun `discover reports versions capabilities and identity`() = runTest {
        val result = parse(session().handle(rpc("server/discover", withMeta()))).resultObject

        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("2026-07-28", "2025-11-25", "2025-06-18"),
            result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(parse("""{"tools":{"listChanged":true}}"""), result["capabilities"])
        assertEquals(
            parse("""{"name":"kora-test","title":"Kora test","version":"1.2.3"}"""),
            result["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"],
        )
        assertEquals("Use the tools.", result["instructions"]!!.jsonPrimitive.content)
        assertEquals(0, result["ttlMs"]!!.jsonPrimitive.content.toInt())
        assertEquals("private", result["cacheScope"]!!.jsonPrimitive.content)
    }

    @Test
    fun `modern requests need no handshake`() = runTest {
        val result = parse(session().handle(rpc("tools/list", withMeta()))).resultObject

        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(listOf("media_library__list_playlists", "media_library__get_playlist_tracks", "media_library__search_tracks"), toolNames(parse(session().handle(rpc("tools/list", withMeta())))))
        assertEquals(0, result["ttlMs"]!!.jsonPrimitive.content.toInt())
        assertEquals("private", result["cacheScope"]!!.jsonPrimitive.content)
        assertTrue("io.modelcontextprotocol/serverInfo" in result["_meta"]!!.jsonObject)
    }

    @Test
    fun `an unsupported version is answered with the supported ones`() = runTest {
        val answer = parse(session().handle(rpc("tools/list", withMeta(version = "1900-01-01"))))

        assertEquals(-32022, answer.errorCode)
        val data = answer["error"]!!.jsonObject["data"]!!.jsonObject
        assertEquals("1900-01-01", data["requested"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("2026-07-28", "2025-11-25", "2025-06-18"),
            data["supported"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a modern request without its required metadata is invalid params`() = runTest {
        val session = session()
        val noCapabilities = parse("""{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}""")
        assertEquals(-32602, parse(session.handle(rpc("tools/list", noCapabilities))).errorCode)
        val badVersion = parse("""{"_meta":{"io.modelcontextprotocol/protocolVersion":5,"io.modelcontextprotocol/clientCapabilities":{}}}""")
        assertEquals(-32602, parse(session.handle(rpc("tools/list", badVersion))).errorCode)
        assertEquals(-32602, parse(session.handle(rpc("tools/list", parse("""{"_meta":"x"}""")))).errorCode)
        assertEquals(-32602, parse(session.handle(rpc("server/discover"))).errorCode)
    }

    @Test
    fun `client identity in the payload changes nothing`() = runTest {
        val spoofed = parse(
            """{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"SELF","version":"0"}}}""",
        )
        assertTrue(toolNames(parse(session().handle(rpc("tools/list", spoofed)))).isNotEmpty())
    }

    // ---- tools/list ---------------------------------------------------------------------------------------------

    @Test
    fun `only reachable capabilities are listed`() = runTest {
        assertEquals(
            listOf("media_library__list_playlists", "media_library__get_playlist_tracks", "media_library__search_tracks"),
            toolNames(parse(session().initialized().handle(rpc("tools/list")))),
        )
        assertEquals(emptyList<String>(), toolNames(parse(session(target = KoraNode("empty")).initialized().handle(rpc("tools/list")))))
    }

    @Test
    fun `a listed tool has name title description and both schemas`() = runTest {
        val tool = parse(session().initialized().handle(rpc("tools/list"))).resultObject["tools"]!!.jsonArray[0].jsonObject

        assertEquals(setOf("name", "title", "description", "inputSchema", "outputSchema"), tool.keys)
        assertEquals("object", tool["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the full catalogue is listed in order and is stable`() = runTest {
        val everything = KoraNode("all").apply { provideEverything() }
        val first = toolNames(parse(session(target = everything).initialized().handle(rpc("tools/list"))))
        val second = toolNames(parse(session(target = everything).initialized().handle(rpc("tools/list"))))

        assertEquals(McpTools.all.map { it.name }, first)
        assertEquals(first, second)
    }

    @Test
    fun `pagination walks the whole list through cursors`() = runTest {
        val everything = KoraNode("all").apply { provideEverything() }
        val session = session(pageSize = 4, target = everything).initialized()
        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = if (cursor == null) null else parse("""{"cursor":"$cursor"}""")
            val result = parse(session.handle(rpc("tools/list", params))).resultObject
            collected += toolNames(parse("""{"result":$result}"""))
            cursor = result["nextCursor"]?.jsonPrimitive?.content
            pages++
        } while (cursor != null)

        assertEquals(McpTools.all.map { it.name }, collected)
        assertEquals((McpTools.all.size + 3) / 4, pages)
    }

    @Test
    fun `the last page has no cursor and a single page needs none`() = runTest {
        val result = parse(session().initialized().handle(rpc("tools/list"))).resultObject
        assertFalse("nextCursor" in result)
    }

    @Test
    fun `an invalid cursor is invalid params`() = runTest {
        val session = session(pageSize = 2).initialized()
        for (cursor in listOf("\"garbage\"", "\"b25lMQ\"", "5", "[1]", "\"\"", "\"b-1\"")) {
            val answer = parse(session.handle(rpc("tools/list", parse("""{"cursor":$cursor}"""))))
            assertEquals(-32602, answer.errorCode, cursor)
        }
        val beyond = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("o999".encodeToByteArray())
        assertEquals(-32602, parse(session.handle(rpc("tools/list", parse("""{"cursor":"$beyond"}""")))).errorCode)
    }
}
