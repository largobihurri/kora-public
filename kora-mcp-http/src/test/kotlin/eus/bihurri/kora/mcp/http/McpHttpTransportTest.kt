package eus.bihurri.kora.mcp.http

import eus.bihurri.kora.contract.media.MediaLibrary
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/** HTTP mechanics: methods, content negotiation, framing, limits and the server's life cycle. */
class McpHttpTransportTest {
    private val fixture = Fixture(config = { testConfig(maxBodyBytes = 1_024) })
    private val agent = fixture.pair()
    private val ping = modernRequest("ping")
    private val pingHeaders = modernHeaders("ping")

    @AfterEach
    fun stop() = fixture.close()

    private fun post(body: String = ping, headers: Map<String, String?> = pingHeaders, token: String? = agent.token) =
        fixture.post(body, token, headers)

    @Test
    fun `GET and DELETE are 405 for a paired client, and 401 for anyone else`() {
        for (method in listOf("GET", "DELETE", "PUT", "HEAD", "OPTIONS")) {
            val paired = fixture.send(method = method, token = agent.token)
            assertEquals(405, paired.status, method)
            assertEquals("POST", paired.headers["allow"], method)
            assertEquals(401, fixture.send(method = method).status, method)
        }
    }

    @Test
    fun `a session id and a Last-Event-ID from an older client are ignored`() {
        val response = post(headers = pingHeaders + mapOf("Mcp-Session-Id" to "abc", "Last-Event-ID" to "7"))

        assertEquals(200, response.status)
        assertNull(response.headers["mcp-session-id"])
    }

    @Test
    fun `another path is 401 without a token, so paths cannot be probed, and 404 with one`() {
        assertEquals(401, fixture.send(path = "/").status)
        assertEquals(401, fixture.send(path = "/admin", body = ping.toByteArray()).status)
        assertEquals(401, fixture.send(path = "/mcp").status)
        assertEquals(fixture.send(path = "/mcp").body, fixture.send(path = "/admin").body)
        assertEquals(fixture.send(path = "/mcp").headers["www-authenticate"], fixture.send(path = "/admin").headers["www-authenticate"])
    }

    @Test
    fun `any other path is 404 and a query string is ignored`() {
        assertEquals(404, fixture.send(path = "/", token = agent.token, body = ping.toByteArray()).status)
        assertEquals(404, fixture.send(path = "/mcp/", token = agent.token, body = ping.toByteArray()).status)
        assertEquals(404, fixture.send(path = "/MCP", token = agent.token, body = ping.toByteArray()).status)
        assertEquals(200, fixture.send(path = "/mcp?x=1", token = agent.token, body = ping.toByteArray(), headers = pingHeaders).status)
    }

    @Test
    fun `the endpoint path is configurable`() {
        val custom = Fixture(config = { testConfig(path = "/agents/kardio") })
        try {
            val paired = custom.pair()

            assertEquals(404, custom.send(path = "/mcp", token = paired.token, body = ping.toByteArray(), headers = pingHeaders).status)
            assertEquals(200, custom.send(path = "/agents/kardio", token = paired.token, body = ping.toByteArray(), headers = pingHeaders).status)
        } finally {
            custom.close()
        }
    }

    @Test
    fun `the content type must be JSON, with no other charset than UTF-8`() {
        for (contentType in listOf("text/plain", "application/x-www-form-urlencoded", "application/jsonp", "application/json; charset=latin1")) {
            assertEquals(415, post(headers = pingHeaders + ("Content-Type" to contentType)).status, contentType)
        }
        assertEquals(415, post(headers = pingHeaders + ("Content-Type" to null)).status)
        for (contentType in listOf("application/json", "Application/JSON", "application/json; charset=utf-8", "application/json;charset=\"UTF-8\"")) {
            assertEquals(200, post(headers = pingHeaders + ("Content-Type" to contentType)).status, contentType)
        }
    }

    @Test
    fun `Accept must allow JSON`() {
        for (accept in listOf("text/event-stream", "text/plain", "application/json;q=0", "application/json; q=0.0, text/event-stream")) {
            assertEquals(406, post(headers = pingHeaders + ("Accept" to accept)).status, accept)
        }
        for (accept in listOf("application/json", "application/json, text/event-stream", "*/*", "application/*", "text/event-stream, application/json;q=0.5")) {
            assertEquals(200, post(headers = pingHeaders + ("Accept" to accept)).status, accept)
        }
        assertEquals(200, post(headers = pingHeaders + ("Accept" to null)).status)
    }

    @Test
    fun `a body above the limit is 413 without being read`() {
        val response = fixture.send(
            body = null,
            token = agent.token,
            headers = pingHeaders + ("Content-Length" to "1025"),
        )

        assertEquals(413, response.status)
    }

    @Test
    fun `a body at the limit is served and one byte more is refused`() {
        val pad = " ".repeat(1_024 - ping.length)
        val exact = (ping + pad)

        assertEquals(1_024, exact.toByteArray().size)
        assertEquals(200, post(exact).status)
        assertEquals(413, post(exact + " ").status)
    }

    @Test
    fun `an unauthenticated oversized body is turned away without reading it`() {
        val response = fixture.send(
            body = ByteArray(8_000) { ' '.code.toByte() },
            token = null,
            headers = pingHeaders,
        )

        assertEquals(401, response.status)
    }

    @Test
    fun `chunked bodies, a missing length and conflicting lengths are refused`() {
        val chunked = fixture.send(token = agent.token, headers = pingHeaders + ("Transfer-Encoding" to "chunked"))
        val missing = fixture.send(token = agent.token, headers = pingHeaders)
        val both = fixture.send(token = agent.token, headers = pingHeaders + mapOf("Transfer-Encoding" to "chunked", "Content-Length" to "4"))
        val twice = fixture.exchange(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer ${agent.token}\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}".toByteArray(),
        )
        val negative = fixture.send(token = agent.token, headers = pingHeaders + ("Content-Length" to "-1"))
        val signed = fixture.send(token = agent.token, headers = pingHeaders + ("Content-Length" to "+4"))

        assertEquals(listOf(411, 411, 400, 400, 400, 400), listOf(chunked, missing, both, twice, negative, signed).map { it.status })
    }

    @Test
    fun `an unsupported Expect is 417 and 100-continue is honoured`() {
        assertEquals(417, post(headers = pingHeaders + ("Expect" to "something")).status)

        val response = post(headers = pingHeaders + ("Expect" to "100-continue"))

        assertEquals(200, response.status)
    }

    @Test
    fun `malformed JSON, an empty body and invalid UTF-8 are 400`() {
        val invalidUtf8 = fixture.send(body = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()), token = agent.token, headers = pingHeaders)

        for (body in listOf("{not json", "", "[")) {
            val response = post(body)
            assertEquals(400, response.status, body)
            assertEquals(-32700, response.errorCode, body)
        }
        assertEquals(400, invalidUtf8.status)
        assertEquals(-32700, invalidUtf8.errorCode)
    }

    @Test
    fun `malformed requests are 400 and never reach authentication`() {
        val cases = mapOf(
            "garbage" to "garbage\r\n\r\n",
            "lowercase method" to "post /mcp HTTP/1.1\r\n\r\n",
            "two spaces" to "POST  /mcp HTTP/1.1\r\n\r\n",
            "absolute form" to "POST http://127.0.0.1/mcp HTTP/1.1\r\n\r\n",
            "no colon" to "POST /mcp HTTP/1.1\r\nBroken\r\n\r\n",
            "space before colon" to "POST /mcp HTTP/1.1\r\nX-A : b\r\n\r\n",
            "folded header" to "POST /mcp HTTP/1.1\r\nX-A: b\r\n c\r\n\r\n",
            "bare LF in a header" to "POST /mcp HTTP/1.1\r\nX-A: b\nX-B: c\r\n\r\n",
            "NUL in a header" to "POST /mcp HTTP/1.1\r\nX-A: b\u0000c\r\n\r\n",
        )

        for ((name, text) in cases) {
            assertEquals(400, fixture.exchange(text.toByteArray(Charsets.ISO_8859_1)).status, name)
        }
        assertEquals(505, fixture.exchange("POST /mcp HTTP/2.0\r\n\r\n".toByteArray()).status)
        assertTrue(fixture.denials.isEmpty())
    }

    @Test
    fun `an oversized head is 431`() {
        val huge = "X-Big: " + "a".repeat(HttpWire.MAX_HEAD_BYTES)
        val many = (1..80).joinToString("") { "X-$it: v\r\n" }

        assertEquals(431, fixture.exchange("POST /mcp HTTP/1.1\r\n$huge\r\n\r\n".toByteArray()).status)
        assertEquals(431, fixture.exchange("POST /mcp HTTP/1.1\r\n$many\r\n".toByteArray()).status)
    }

    @Test
    fun `HTTP 1 0 requests are served and the connection is always closed`() {
        val response = fixture.send(requestLine = "POST /mcp HTTP/1.0", body = ping.toByteArray(), token = agent.token, headers = pingHeaders)

        assertEquals(200, response.status)
        assertEquals("close", response.headers["connection"])
    }

    @Test
    fun `a connection that sends nothing is dropped after the read timeout`() {
        val impatient = Fixture(config = { testConfig(headTimeout = 300.milliseconds) })
        try {
            Socket(InetAddress.getLoopbackAddress(), impatient.port).use { socket ->
                socket.soTimeout = 5_000
                val started = System.nanoTime()
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-".toByteArray())

                assertEquals(-1, socket.getInputStream().read())
                assertTrue((System.nanoTime() - started) < 4_000_000_000)
            }
        } finally {
            impatient.close()
        }
    }

    @Test
    fun `connections above the limit are answered 503 and the server recovers`() {
        val tight = Fixture(config = { testConfig(maxConnections = 1, headTimeout = 5_000.milliseconds) })
        try {
            val paired = tight.pair()
            Socket(InetAddress.getLoopbackAddress(), tight.port).use { holder ->
                holder.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
                holder.getOutputStream().flush()
                // Wait until the acceptor has handed the first connection to its worker.
                Thread.sleep(200)

                val busy = tight.send(token = paired.token, body = ping.toByteArray(), headers = pingHeaders)

                assertEquals(503, busy.status)
                assertEquals("1", busy.headers["retry-after"])
            }
            // The holder is gone: the slot frees up.
            var status = 0
            repeat(50) {
                if (status != 200) {
                    status = tight.send(token = paired.token, body = ping.toByteArray(), headers = pingHeaders).status
                    if (status != 200) Thread.sleep(50)
                }
            }
            assertEquals(200, status)
        } finally {
            tight.close()
        }
    }

    @Test
    fun `a node that does not answer in time is 504`() {
        val slow = Fixture(config = { testConfig(requestTimeout = 200.milliseconds) }, beforeList = { kotlinx.coroutines.delay(5_000) })
        try {
            val paired = slow.pair(grant = setOf(MediaLibrary.ReadScope))
            val body = modernRequest("tools/call", """"name":"media_library__list_playlists","arguments":{}""")

            val response = slow.post(body, paired.token, modernHeaders("tools/call", "media_library__list_playlists"))

            assertEquals(504, response.status)
            assertEquals(-32603, response.errorCode)
        } finally {
            slow.close()
        }
    }

    @Test
    fun `stop frees the port, drops what is in flight and can be called twice`() {
        val port = fixture.port
        assertTrue(fixture.server.isRunning)
        val inFlight = Socket(InetAddress.getLoopbackAddress(), port).apply {
            soTimeout = 5_000
            getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
        }

        fixture.server.stop()
        fixture.server.stop()

        assertFalse(fixture.server.isRunning)
        assertNull(fixture.server.port)
        assertEquals(-1, runCatching { inFlight.getInputStream().read() }.getOrDefault(-1))
        inFlight.close()
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
        assertThrows<IOException> { Socket(InetAddress.getLoopbackAddress(), port).close() }
    }

    @Test
    fun `the server can be started again after a stop, with the same pairings`() {
        fixture.server.stop()

        val port = fixture.server.start(InetAddress.getLoopbackAddress(), 0)
        val response = Fixture.parseResponse(rawPing(port, agent.token))

        assertEquals(200, response.status)
    }

    @Test
    fun `starting twice, on the wildcard address or on a bad port is refused`() {
        assertThrows<IllegalStateException> { fixture.server.start(InetAddress.getLoopbackAddress(), 0) }
        fixture.server.stop()
        assertThrows<IllegalArgumentException> { fixture.server.start(InetAddress.getByName("0.0.0.0"), 0) }
        assertThrows<IllegalArgumentException> { fixture.server.start(InetAddress.getByName("::"), 0) }
        assertThrows<IllegalArgumentException> { fixture.server.start(InetAddress.getLoopbackAddress(), 70_000) }
        assertFalse(fixture.server.isRunning)
    }

    @Test
    fun `an address that is not ours cannot be bound`() {
        fixture.server.stop()

        val permissive = Fixture(config = { testConfig(allowNonTailnetAddress = true) })
        permissive.server.stop()

        assertThrows<IOException> { permissive.server.start(InetAddress.getByName("203.0.113.9"), 0) }
        assertFalse(permissive.server.isRunning)
    }

    @Test
    fun `it listens on the given address only`() {
        val loopback = InetAddress.getLoopbackAddress()
        val reachableElsewhere = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        if (reachableElsewhere == null) return

        val refused = runCatching { Socket(reachableElsewhere, fixture.port).close() }

        assertTrue(refused.isFailure, "the server answered on ${reachableElsewhere.hostAddress} although bound to $loopback")
    }

    @Test
    fun `configuration is validated`() {
        assertThrows<IllegalArgumentException> { McpHttpConfig(path = "mcp") }
        assertThrows<IllegalArgumentException> { McpHttpConfig(path = "/mcp?x") }
        assertThrows<IllegalArgumentException> { McpHttpConfig(maxBodyBytes = 0) }
        assertThrows<IllegalArgumentException> { McpHttpConfig(maxConnections = 0) }
        assertThrows<IllegalArgumentException> { McpHttpConfig(readTimeout = 0.milliseconds) }
        assertThrows<IllegalArgumentException> { McpHttpConfig(headTimeout = 0.milliseconds) }
        assertThrows<IllegalArgumentException> { McpHttpConfig(maxConnectionsPerAddress = 0) }
        assertThrows<IllegalArgumentException> { McpHttpConfig(deniedThrottle = (-1).milliseconds) }
    }

    private fun rawPing(port: Int, token: String): ByteArray {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val bytes = ping.toByteArray()
            val head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer $token\r\n" +
                "MCP-Protocol-Version: $MODERN\r\nMcp-Method: ping\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray() + bytes)
            return socket.getInputStream().readBytes()
        }
    }
}
