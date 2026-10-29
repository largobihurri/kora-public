package eus.bihurri.kora.mcp.http

import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** What a paired agent can do: both MCP eras, per-pairing grants, and the MCP headers the specification adds. */
class McpHttpProtocolTest {
    private val fixture = Fixture()
    private val agent = fixture.pair("claude", grant = setOf(MediaLibrary.ReadScope))
    private val listCall = """"name":"media_library__list_playlists","arguments":{"limit":2}"""

    @AfterEach
    fun stop() = fixture.close()

    private val Response.result: JsonObject get() = json()["result"]!!.jsonObject
    private val Response.isToolError: Boolean get() = result["isError"]!!.jsonPrimitive.boolean

    private fun JsonObject.toolText() = this["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `initialize of 2025-11-25 is answered as JSON and no session id is minted`() {
        val response = fixture.post(legacyInitialize(), agent.token, mapOf("MCP-Protocol-Version" to null))

        assertEquals(200, response.status)
        assertEquals("application/json", response.headers["content-type"])
        assertEquals("2025-11-25", response.result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("kardio-test", response.result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(response.headers["mcp-session-id"])
        assertEquals("no-store", response.headers["cache-control"])
    }

    @Test
    fun `a stateless server discover of 2026-07-28 is answered`() {
        val response = fixture.post(modernRequest("server/discover"), agent.token, modernHeaders("server/discover"))

        assertEquals(200, response.status)
        assertEquals("complete", response.result["resultType"]!!.jsonPrimitive.content)
        assertTrue(response.result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(MODERN))
    }

    @Test
    fun `the legacy handshake works across requests, and a header with the negotiated version is accepted`() {
        val headers = mapOf("MCP-Protocol-Version" to "2025-11-25")
        fixture.post(legacyInitialize(), agent.token)

        val initialized = fixture.post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", agent.token, headers)
        val list = fixture.post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", agent.token, headers)
        val call = fixture.post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{$listCall}}""", agent.token, headers)

        assertEquals(202, initialized.status)
        assertEquals(200, list.status)
        assertTrue(list.result["tools"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "media_library__list_playlists" })
        assertFalse(call.isToolError)
        assertEquals(2, call.result["structuredContent"]!!.jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun `tools list and a granted tools call work in the stateless era`() {
        val list = fixture.post(modernRequest("tools/list"), agent.token, modernHeaders("tools/list"))
        val call = fixture.post(
            modernRequest("tools/call", listCall),
            agent.token,
            modernHeaders("tools/call", "media_library__list_playlists"),
        )

        assertEquals(200, list.status)
        assertEquals(200, call.status)
        assertFalse(call.isToolError)
        assertEquals(3, call.result["structuredContent"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a pairing without the grant gets the unauthorized tool error, per pairing`() {
        val other = fixture.pair("other")
        val headers = modernHeaders("tools/call", "media_library__list_playlists")

        val allowed = fixture.post(modernRequest("tools/call", listCall), agent.token, headers)
        val refused = fixture.post(modernRequest("tools/call", listCall), other.token, headers)

        assertEquals(200, refused.status)
        assertTrue(refused.isToolError)
        assertTrue(refused.result.toolText().startsWith("unauthorized"), refused.body)
        assertFalse(allowed.isToolError)
        assertEquals(
            setOf(Caller(CallerKind.REMOTE_AGENT, "tailnet:${agent.pairing.id}"), Caller(CallerKind.REMOTE_AGENT, "tailnet:${other.pairing.id}")),
            fixture.audit.entries.map { it.caller }.toSet(),
        )
    }

    @Test
    fun `a grant given after the first call takes effect, and the payload cannot pick the identity`() {
        val late = fixture.pair("late")
        val headers = modernHeaders("tools/call", "media_library__list_playlists")
        // The body claims to be another pairing; only the token counts.
        val claiming = modernRequest("tools/call", listCall).replace("\"name\":\"t\"", "\"name\":\"tailnet:${agent.pairing.id}\"")

        assertTrue(fixture.post(claiming, late.token, headers).isToolError)
        fixture.access.grants[late.caller] = setOf(MediaLibrary.ReadScope)

        assertFalse(fixture.post(claiming, late.token, headers).isToolError)
        assertEquals(setOf(late.caller), fixture.audit.entries.map { it.caller }.toSet())
    }

    @Test
    fun `a notification and a response are accepted with 202 and no body`() {
        val notification = fixture.post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", agent.token)
        val response = fixture.post("""{"jsonrpc":"2.0","id":9,"result":{}}""", agent.token)

        assertEquals(202, notification.status)
        assertEquals("", notification.body)
        assertEquals("0", notification.headers["content-length"])
        assertEquals(202, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `an unknown method is the JSON-RPC error of the session, not an HTTP failure`() {
        val response = fixture.post(modernRequest("nope"), agent.token, modernHeaders("nope"))

        assertEquals(200, response.status)
        assertEquals(-32601, response.errorCode)
    }

    @Test
    fun `a batch gets the invalid request error of the session`() {
        val response = fixture.post("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""", agent.token)

        assertEquals(200, response.status)
        assertEquals(-32600, response.errorCode)
    }

    @Test
    fun `an unsupported protocol version header is 400 with the supported list`() {
        val response = fixture.post(legacyInitialize(), agent.token, mapOf("MCP-Protocol-Version" to "1999-01-01"))

        assertEquals(400, response.status)
        assertEquals(-32022, response.errorCode)
        val data = response.json()["error"]!!.jsonObject["data"]!!.jsonObject
        assertTrue((data["supported"] as JsonArray).map { it.jsonPrimitive.content }.contains(MODERN))
        assertEquals("1999-01-01", data["requested"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a stateless request must carry a matching protocol version header`() {
        val body = modernRequest("ping")

        val missing = fixture.post(body, agent.token, mapOf("Mcp-Method" to "ping"))
        val differs = fixture.post(body, agent.token, modernHeaders("ping", version = "2025-11-25"))
        val modernHeaderLegacyBody = fixture.post(legacyInitialize(), agent.token, mapOf("MCP-Protocol-Version" to MODERN))

        for (response in listOf(missing, differs, modernHeaderLegacyBody)) {
            assertEquals(400, response.status, response.body)
            assertEquals(-32020, response.errorCode)
        }
    }

    @Test
    fun `a stateless request must carry Mcp-Method, matching the body`() {
        val body = modernRequest("tools/list")

        val missing = fixture.post(body, agent.token, mapOf("MCP-Protocol-Version" to MODERN))
        val differs = fixture.post(body, agent.token, modernHeaders("ping"))

        assertEquals(listOf(400, 400), listOf(missing, differs).map { it.status })
        assertEquals(listOf(-32020, -32020), listOf(missing, differs).map { it.errorCode })
    }

    @Test
    fun `a stateless tools call must carry Mcp-Name, matching params name, plain or Base64 encoded`() {
        val body = modernRequest("tools/call", listCall)
        val name = "media_library__list_playlists"
        val encoded = "=?base64?${Base64.getEncoder().encodeToString(name.toByteArray())}?="

        val missing = fixture.post(body, agent.token, modernHeaders("tools/call"))
        val differs = fixture.post(body, agent.token, modernHeaders("tools/call", "media_library__search_tracks"))
        val plain = fixture.post(body, agent.token, modernHeaders("tools/call", name))
        val base64 = fixture.post(body, agent.token, modernHeaders("tools/call", encoded))
        val brokenBase64 = fixture.post(body, agent.token, modernHeaders("tools/call", "=?base64?%%%?="))

        assertEquals(listOf(400, 400, 200, 200, 400), listOf(missing, differs, plain, base64, brokenBase64).map { it.status })
    }

    @Test
    fun `a stateless notification needs no Mcp-Method`() {
        val body = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"_meta":${modernMeta()}}}"""

        assertEquals(202, fixture.post(body, agent.token, mapOf("MCP-Protocol-Version" to MODERN)).status)
    }

    @Test
    fun `evicting a session forgets the legacy handshake`() {
        val headers = mapOf("MCP-Protocol-Version" to "2025-11-25")
        fixture.post(legacyInitialize(), agent.token)
        val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        assertEquals(200, fixture.post(list, agent.token, headers).status)
        assertTrue(fixture.post(list, agent.token, headers).json().containsKey("result"))

        fixture.server.evictSession(agent.pairing.id)

        assertEquals(-32602, fixture.post(list, agent.token, headers).errorCode)
    }

    @Test
    fun `a real HTTP client can talk to it`() {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${fixture.port}/mcp"))
            .header("Authorization", "Bearer ${agent.token}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(legacyInitialize()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"protocolVersion\":\"2025-11-25\""))
    }
}
