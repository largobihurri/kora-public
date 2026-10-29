package eus.bihurri.kora.android

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.testing.provideStubbed
import eus.bihurri.kora.mcp.McpServerInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

class McpGateTest {
    private val shell = Caller(CallerKind.SHELL, "shell")
    private val app = Caller(CallerKind.EXTERNAL_APP, "com.example.agent", "a".repeat(64))
    private val grants = mutableMapOf<Caller, Set<Scope>>(shell to setOf(MediaLibrary.ReadScope))

    private val access = object : ExternalAccess {
        override suspend fun isEnabled() = true

        override suspend fun grantedScopes(caller: Caller): Set<Scope> = grants[caller].orEmpty()
    }

    private val node = KoraNode("sonora", policy = DefaultCallerPolicy(access)).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { Page(listOf(PlaylistSummary("pl-1", "Chill \"mix\" \$HOME `x`", 3)), 1) }
        }
    }

    private fun gate(maxBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES) =
        McpGate(node, McpServerInfo("sonora", "1.0", title = "Sonora"), 1.seconds, maxBytes)

    private fun rpc(id: Int?, method: String, params: String = "{}") =
        """{"jsonrpc":"2.0",${if (id != null) "\"id\":$id," else ""}"method":"$method","params":$params}"""

    private suspend fun McpGate.plain(caller: Caller, message: String): JsonObject? {
        val reply = assertInstanceOf(McpGate.Answer.Reply::class.java, handle(caller, message))
        assertFalse(reply.encoded)
        return reply.message?.let { Json.parseToJsonElement(it).jsonObject }
    }

    private suspend fun McpGate.handshake(caller: Caller) {
        plain(caller, rpc(1, "initialize", """{"protocolVersion":"2025-11-25","capabilities":{}}"""))
        assertNull(plain(caller, rpc(null, "notifications/initialized")))
    }

    private val list = rpc(3, "tools/call", """{"name":"media_library__list_playlists","arguments":{}}""")

    private fun JsonObject.result() = this["result"]!!.jsonObject

    private fun JsonObject.isError() = result()["isError"]!!.jsonPrimitive.booleanOrNull!!

    private fun JsonObject.text() = result()["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun b64(text: String) = KoraGate.PREFIX_BASE64 + Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())

    private fun unb64(text: String) = Base64.getUrlDecoder().decode(text).decodeToString()

    @Test
    fun `concurrent first calls from one caller leave one usable session`() = runTest {
        val gate = gate()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val initializations = (1..8).map { i ->
                pool.submit<Any?> {
                    start.await()
                    kotlinx.coroutines.runBlocking {
                        gate.handle(shell, rpc(i, "initialize", """{"protocolVersion":"2025-11-25","capabilities":{}}"""))
                    }
                }
            }
            start.countDown()
            initializations.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertNull(gate.plain(shell, rpc(null, "notifications/initialized")))
        val answer = gate.plain(shell, list)!!
        assertFalse(answer.isError(), answer.toString())
    }

    @Test
    fun `the handshake state survives separate calls`() = runTest {
        val gate = gate()
        gate.handshake(shell)

        val tools = gate.plain(shell, rpc(2, "tools/list"))!!

        assertTrue(tools.result()["tools"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "media_library__list_playlists" })
        val called = gate.plain(shell, list)!!
        assertFalse(called.isError())
        assertEquals("pl-1", called.result()["structuredContent"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a request before the handshake is told to initialize first`() = runTest {
        val answer = gate().plain(shell, rpc(1, "tools/list"))!!

        assertEquals(-32602, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(answer["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("initialize first"))
    }

    @Test
    fun `a notification has no answer`() = runTest {
        val reply = assertInstanceOf(McpGate.Answer.Reply::class.java, gate().handle(shell, rpc(null, "notifications/initialized")))

        assertNull(reply.message)
    }

    @Test
    fun `a tool the caller was not granted is a tool error`() = runTest {
        grants.clear()
        val gate = gate()
        gate.handshake(shell)

        val answer = gate.plain(shell, list)!!

        assertTrue(answer.isError())
        assertTrue(answer.text().startsWith("unauthorized"))
    }

    @Test
    fun `each caller has its own session and its own grants`() = runTest {
        val gate = gate()
        gate.handshake(shell)

        // The app never initialized, so its session refuses; the shell's handshake is not shared.
        assertEquals(-32602, gate.plain(app, rpc(2, "tools/list"))!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        gate.handshake(app)
        assertTrue(gate.plain(app, list)!!.isError())
        assertFalse(gate.plain(shell, list)!!.isError())
    }

    @Test
    fun `the identity comes from the caller, never from client info`() = runTest {
        val gate = gate()
        gate.plain(
            app,
            rpc(1, "initialize", """{"protocolVersion":"2025-11-25","clientInfo":{"name":"shell","version":"1"},"caller":"shell"}"""),
        )
        gate.plain(app, rpc(null, "notifications/initialized"))

        assertTrue(gate.plain(app, list)!!.isError())
    }

    @Test
    fun `the node itself and its peers are not served`() = runTest {
        for (kind in listOf(CallerKind.SELF, CallerKind.PEER)) {
            val refused = assertInstanceOf(McpGate.Answer.Refused::class.java, gate().handle(Caller(kind, "x"), rpc(1, "ping")))

            assertEquals(ErrorCode.UNAUTHORIZED, refused.code)
        }
    }

    @Test
    fun `an encoded request is answered encoded and survives hostile characters`() = runTest {
        val gate = gate()
        val nasty = "q\"uote \\ back \$HOME `tick` 'single' ; | & > < * ? ~ áé こん 🎧 {x}"
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 5)
            put("method", "tools/call")
            put("params", buildJsonObject { put("name", nasty); put("arguments", buildJsonObject { }) })
        }.toString()
        gate.plain(shell, rpc(1, "initialize", """{"protocolVersion":"2025-11-25"}"""))
        val encoded = b64(message)
        assertTrue(encoded.all { it.isLetterOrDigit() || it in "-_:" }, "the argument needs no shell quoting")

        val reply = assertInstanceOf(McpGate.Answer.Reply::class.java, gate.handle(shell, encoded))

        assertTrue(reply.encoded)
        assertTrue(reply.message!!.all { it.isLetterOrDigit() || it in "-_" }, "the answer needs no escaping either")
        val answer = Json.parseToJsonElement(unb64(reply.message)).jsonObject
        assertEquals(-32602, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        // The server echoes the name it was sent (up to a limit): the characters came through intact.
        assertEquals("Unknown tool: " + nasty, answer["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an encoded notification has no answer`() = runTest {
        val reply = assertInstanceOf(McpGate.Answer.Reply::class.java, gate().handle(shell, b64(rpc(null, "notifications/initialized"))))

        assertNull(reply.message)
        assertTrue(reply.encoded)
    }

    @Test
    fun `an argument that is not UTF-8 Base64 is invalid`() = runTest {
        val gate = gate()
        val notBase64 = assertInstanceOf(McpGate.Answer.Refused::class.java, gate.handle(shell, "b64:***"))
        val badUtf8 = KoraGate.PREFIX_BASE64 + Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        val notUtf8 = assertInstanceOf(McpGate.Answer.Refused::class.java, gate.handle(shell, badUtf8))

        assertEquals(ErrorCode.INVALID_PAYLOAD, notBase64.code)
        assertEquals(ErrorCode.INVALID_PAYLOAD, notUtf8.code)
    }

    @Test
    fun `a malformed message is a JSON-RPC parse error, not a gate refusal`() = runTest {
        val answer = gate().plain(shell, "{not json")!!

        assertEquals(-32700, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(kotlinx.serialization.json.JsonNull, answer["id"])
    }

    @Test
    fun `a max-size artwork answer fits the gate limit as an image block`() = runTest {
        val bytes = ByteArray(MediaArtwork.MAX_ARTWORK_BYTES) { (it * 31 + 7).toByte() }
        val artwork = KoraNode("sonora", policy = DefaultCallerPolicy(access)).apply {
            provideStubbed(MediaArtwork) { on(MediaArtwork.GetArtwork) { ArtworkImage(bytes, ArtworkMimeType.JPEG, 1024, 1024) } }
        }
        grants[shell] = setOf(MediaArtwork.ReadScope)
        val gate = McpGate(artwork, McpServerInfo("sonora", "1.0"), 1.seconds)
        gate.handshake(shell)

        val reply = assertInstanceOf(
            McpGate.Answer.Reply::class.java,
            gate.handle(shell, rpc(4, "tools/call", """{"name":"media_artwork__get_artwork","arguments":{"artworkId":"a"}}""")),
        )

        assertTrue(reply.message!!.encodeToByteArray().size < PayloadLimits.MAX_ENVELOPE_BYTES)
        val answer = Json.parseToJsonElement(reply.message).jsonObject
        assertFalse(answer.isError(), answer.result()["content"].toString().take(200))
        val image = answer.result()["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", image["mimeType"]!!.jsonPrimitive.content)
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content)))
    }

    @Test
    fun `oversized requests and answers are refused`() = runTest {
        val gate = gate(maxBytes = 400)
        val padding = "x".repeat(500)

        val request = assertInstanceOf(McpGate.Answer.Refused::class.java, gate.handle(shell, rpc(1, "ping", """{"pad":"$padding"}""")))
        val encoded = assertInstanceOf(McpGate.Answer.Refused::class.java, gate.handle(shell, b64(rpc(1, "ping", """{"pad":"$padding"}"""))))
        // tools/list is far larger than 400 bytes.
        gate.plain(shell, rpc(1, "initialize", """{"protocolVersion":"2025-11-25"}"""))
        val response = assertInstanceOf(McpGate.Answer.Refused::class.java, gate.handle(shell, rpc(2, "tools/list")))

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, request.code)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, encoded.code)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, response.code)
    }
}
