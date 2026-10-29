package eus.bihurri.kora.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader

class McpBridgeTest {
    private val sent = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var device: (String) -> String? = { null }

    private fun run(vararg lines: String): List<String> {
        val out = StringBuilder()
        McpBridge(
            channel = { message -> sent += message; device(message) },
            input = BufferedReader(StringReader(lines.joinToString("\n"))),
            output = out,
            log = { logs += it },
        ).run()
        return out.lines().filter { it.isNotEmpty() }
    }

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private val ping = """{"jsonrpc":"2.0","id":7,"method":"ping"}"""
    private val pong = """{"jsonrpc":"2.0","id":7,"result":{}}"""

    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `each line is forwarded in order and each answer is one line`() {
        device = { if ("ping" in it) pong else """{"jsonrpc":"2.0","id":8,"result":{"tools":[]}}""" }

        val out = run(ping, """{"jsonrpc":"2.0","id":8,"method":"tools/list"}""")

        assertEquals(listOf(pong, """{"jsonrpc":"2.0","id":8,"result":{"tools":[]}}"""), out)
        assertEquals(listOf(ping, """{"jsonrpc":"2.0","id":8,"method":"tools/list"}"""), sent)
    }

    @Test
    fun `notifications produce no output`() {
        val out = run("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertTrue(out.isEmpty())
        assertEquals(1, sent.size)
    }

    @Test
    fun `blank lines and a byte order mark are skipped`() {
        device = { pong }

        val out = run("\uFEFF$ping", "", "   ")

        assertEquals(listOf(pong), out)
        assertEquals(listOf(ping), sent)
    }

    @Test
    fun `a line that is not JSON is a parse error and never reaches the phone`() {
        val out = run("{not json", "plain text", "junk")

        assertEquals(3, out.size)
        for (line in out) {
            assertEquals(-32700, obj(line).errorCode())
            assertEquals(JsonNull, obj(line)["id"])
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `valid JSON that is not an object is an invalid request and never reaches the phone`() {
        val out = run("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""", "42", "\"text\"")

        assertEquals(3, out.size)
        for (line in out) assertEquals(-32600, obj(line).errorCode())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a device failure becomes an error with the id of the request`() {
        device = { throw DeviceException("device offline") }

        val out = run(ping, """{"jsonrpc":"2.0","id":"abc","method":"tools/list"}""")

        assertEquals(JsonPrimitive(7), obj(out[0])["id"])
        assertEquals(JsonPrimitive("abc"), obj(out[1])["id"])
        assertEquals(-32000, obj(out[0]).errorCode())
        assertTrue(obj(out[0])["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("device offline"))
        assertTrue(logs.any { "device offline" in it })
    }

    @Test
    fun `a refusal by the gate keeps its code in data`() {
        device = { throw DeviceException("The app refused the call", code = "unauthorized") }

        val error = obj(run(ping).single())["error"]!!.jsonObject

        assertEquals("unauthorized", error["data"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a device failure on a notification is only logged`() {
        device = { throw DeviceException("device offline") }

        val out = run("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertTrue(out.isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun `a request the app does not answer is an internal error`() {
        device = { null }

        assertEquals(-32603, obj(run(ping).single()).errorCode())
    }

    @Test
    fun `the loop survives failures and keeps serving`() {
        var first = true
        device = { if (first) { first = false; throw DeviceException("flaky") } else pong }

        val out = run(ping, ping)

        assertEquals(-32000, obj(out[0]).errorCode())
        assertEquals(pong, out[1])
    }

    @Test
    fun `a reply with a raw newline is re-emitted on one line`() {
        device = { "{\n \"jsonrpc\": \"2.0\",\n \"id\": 7,\n \"result\": {}\n}" }

        assertEquals(pong, run(ping).single())
    }

    @Test
    fun `a lost session is repaired by replaying the handshake once`() {
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""
        val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        val lost = """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Missing _meta; others must call initialize first"}}"""
        val ok = """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""
        var restarted = false
        device = { message ->
            when {
                "initialize" in message && "notifications" !in message -> """{"jsonrpc":"2.0","id":1,"result":{}}"""
                "notifications/initialized" in message -> null
                !restarted -> { restarted = true; lost }
                else -> ok
            }
        }

        val out = run(initialize, initialized, list)

        assertEquals(listOf("""{"jsonrpc":"2.0","id":1,"result":{}}""", ok), out)
        // initialize, initialized, tools/list (lost), replayed initialize, replayed initialized, tools/list again
        assertEquals(listOf(initialize, initialized, list, initialize, initialized, list), sent)
    }

    @Test
    fun `a lost session without a remembered handshake is passed on`() {
        val lost = """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"call initialize first"}}"""
        device = { lost }

        assertEquals(listOf(lost), run("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        assertEquals(1, sent.size)
    }

    @Test
    fun `an invalid params error that is not a lost session is not retried`() {
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""
        val bad = """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown tool: x"}}"""
        device = { if ("initialize" in it) """{"jsonrpc":"2.0","id":1,"result":{}}""" else bad }

        val out = run(initialize, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"x"}}""")

        assertEquals(bad, out.last())
        assertEquals(2, sent.size)
    }
}
