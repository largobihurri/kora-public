package eus.bihurri.kora.mcp.http

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Who gets in: bearer tokens, revocation, Origin, and what the hooks and the log see. */
class McpHttpAuthTest {
    private val fixture = Fixture()
    private val ping = modernRequest("ping")
    private val pingHeaders = modernHeaders("ping")

    @AfterEach
    fun stop() = fixture.close()

    @Test
    fun `no token is 401 with a Bearer challenge`() {
        val response = fixture.post(ping, token = null, headers = pingHeaders)

        assertEquals(401, response.status)
        assertEquals("Bearer", response.headers["www-authenticate"])
        assertEquals(listOf(DenialReason.MISSING_TOKEN), fixture.denials.map { it.reason })
        assertEquals("127.0.0.1", fixture.denials.single().remoteAddress)
    }

    @Test
    fun `a wrong token is 401 and counted as invalid`() {
        fixture.pair()

        val response = fixture.post(ping, token = PairingTokens.generate().token, headers = pingHeaders)

        assertEquals(401, response.status)
        assertTrue(response.headers.getValue("www-authenticate").startsWith("Bearer"))
        assertEquals(listOf(DenialReason.INVALID_TOKEN), fixture.denials.map { it.reason })
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `a revoked pairing is refused on its next request`() {
        val agent = fixture.pair()
        assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)

        fixture.store.revoke(agent.pairing.id)

        assertEquals(401, fixture.post(ping, agent.token, pingHeaders).status)
        assertEquals(listOf(DenialReason.INVALID_TOKEN), fixture.denials.map { it.reason })
    }

    @Test
    fun `other schemes, empty and malformed tokens are refused`() {
        val agent = fixture.pair()
        val attempts = listOf(
            "Basic ${agent.token}",
            "Bearer",
            "Bearer ",
            "Bearer ${agent.token}x",
            "Bearer ${agent.token.take(40)}",
            "Bearer a b",
            "Bearer ${"a".repeat(PairingTokens.MAX_TOKEN_CHARS + 1)}",
            "Bearer é",
            agent.token,
        )

        for (authorization in attempts) {
            val response = fixture.post(ping, token = null, headers = pingHeaders + ("Authorization" to authorization))
            assertEquals(401, response.status, authorization.take(30))
        }
        assertEquals(attempts.size, fixture.denials.size)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `the Bearer scheme is case-insensitive`() {
        val agent = fixture.pair()

        val response = fixture.post(ping, token = null, headers = pingHeaders + ("Authorization" to "bEaReR ${agent.token}"))

        assertEquals(200, response.status)
    }

    @Test
    fun `two Authorization headers are refused`() {
        val agent = fixture.pair()

        val response = fixture.exchange(
            ("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer ${agent.token}\r\n" +
                "Authorization: Bearer ${agent.token}\r\nContent-Length: 0\r\n\r\n").toByteArray(),
        )

        assertEquals(400, response.status)
    }

    @Test
    fun `the token is never in the payload's reach, and each pairing is its own caller`() {
        val one = fixture.pair("one")
        val two = fixture.pair("two")

        fixture.post(modernRequest("ping"), one.token, pingHeaders)
        fixture.post(modernRequest("ping"), two.token, pingHeaders)

        assertEquals(listOf(one.pairing, two.pairing), fixture.requests.toList())
        assertEquals("tailnet:${one.pairing.id}", one.caller.id)
        assertTrue(one.caller != two.caller)
    }

    @Test
    fun `any Origin is refused by default, before the token is looked at`() {
        val agent = fixture.pair()

        val withToken = fixture.post(ping, agent.token, pingHeaders + ("Origin" to "https://evil.example"))
        val withoutToken = fixture.post(ping, null, pingHeaders + ("Origin" to "http://127.0.0.1:8080"))
        val sandboxed = fixture.post(ping, agent.token, pingHeaders + ("Origin" to "null"))

        assertEquals(listOf(403, 403, 403), listOf(withToken, withoutToken, sandboxed).map { it.status })
        assertEquals(List(3) { DenialReason.FOREIGN_ORIGIN }, fixture.denials.map { it.reason })
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `an allowed Origin passes, compared without regard to case`() {
        fixture.close()
        val allowing = Fixture(config = {
            testConfig(allowedOrigins = setOf("https://app.example"))
        })
        try {
            val agent = allowing.pair()

            assertEquals(200, allowing.post(ping, agent.token, pingHeaders + ("Origin" to "HTTPS://App.Example")).status)
            assertEquals(403, allowing.post(ping, agent.token, pingHeaders + ("Origin" to "https://other.example")).status)
        } finally {
            allowing.close()
        }
    }

    @Test
    fun `a request without Origin is fine, which is what agents send`() {
        val agent = fixture.pair()

        assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
    }

    @Test
    fun `the request hook runs for authenticated requests only`() {
        val agent = fixture.pair()

        fixture.post(ping, null, pingHeaders)
        fixture.post(ping, agent.token, pingHeaders)
        fixture.post(ping, agent.token, pingHeaders)

        assertEquals(listOf(agent.pairing, agent.pairing), fixture.requests.toList())
    }

    @Test
    fun `a listener that throws does not stop the service`() {
        fixture.close()
        val agent = PairingTokens.generate()
        val store = InMemoryPairingStore().apply { add(Pairing("p1", "agent"), agent.hash) }
        val server = McpHttpServer(
            node = fixture.node,
            serverInfo = eus.bihurri.kora.mcp.McpServerInfo("t", "1"),
            pairings = store,
            config = testConfig(),
            listener = object : McpHttpListener {
                override fun onRequest(pairing: Pairing) = error("boom")

                override fun onDenied(attempt: DeniedAttempt) = error("boom")
            },
        )
        val port = server.start(java.net.InetAddress.getLoopbackAddress(), 0)
        try {
            val probe = Fixture.parseResponse(rawPost(port, ping, "Bearer ${agent.token}"))
            val denied = Fixture.parseResponse(rawPost(port, ping, null))

            assertEquals(200, probe.status)
            assertEquals(401, denied.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the log has method, path, status and pairing id, and never a token, header or body`() {
        val agent = fixture.pair()
        val secretBody = modernRequest("ping").replace("\"t\"", "\"body-marker-7f3a\"")

        fixture.post(secretBody, agent.token, pingHeaders + ("X-Marker" to "header-marker-91c2"))
        fixture.post(secretBody, "wrong-token-marker-55d0", pingHeaders)
        fixture.send(method = "GET", path = "/other?q=query-marker-1b8e", token = agent.token)
        waitForLogs(3)

        val text = fixture.logs.joinToString("\n")
        for (secret in listOf(agent.token, "wrong-token-marker-55d0", "body-marker-7f3a", "header-marker-91c2", "query-marker-1b8e")) {
            assertFalse(text.contains(secret), "log leaks $secret")
        }
        assertTrue(fixture.logs.contains("POST /mcp -> 200 pairing=${agent.pairing.id}"), text)
        assertTrue(fixture.logs.contains("POST /mcp -> 401 pairing=-"), text)
        assertTrue(fixture.logs.contains("GET <other> -> 404 pairing=${agent.pairing.id}"), text)
    }

    private fun waitForLogs(count: Int) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (fixture.logs.count { it.contains("->") } < count && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private fun rawPost(port: Int, body: String, authorization: String?): ByteArray {
        java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val bytes = body.toByteArray()
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nAccept: application/json\r\n")
                append("MCP-Protocol-Version: $MODERN\r\nMcp-Method: ping\r\n")
                authorization?.let { append("Authorization: $it\r\n") }
                append("Content-Length: ${bytes.size}\r\n\r\n")
            }
            socket.getOutputStream().write(head.toByteArray() + bytes)
            return socket.getInputStream().readBytes()
        }
    }
}
