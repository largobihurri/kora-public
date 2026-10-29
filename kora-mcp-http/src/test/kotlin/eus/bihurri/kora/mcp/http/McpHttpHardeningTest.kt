package eus.bihurri.kora.mcp.http

import eus.bihurri.kora.contract.media.MediaLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a hostile or broken environment must not be able to do: crash it, starve it, rebind it, keep a revoked session. */
class McpHttpHardeningTest {
    private val ping = modernRequest("ping")
    private val pingHeaders = modernHeaders("ping")
    private val loopback = InetAddress.getLoopbackAddress()

    /** Fails the test if any thread dies of an uncaught exception while [block] runs: on Android that kills the app. */
    private fun <T> noUncaught(block: () -> T): T {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = CopyOnWriteArrayList<String>()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += "${thread.name}: $error" }
        try {
            val result = block()
            Thread.sleep(100)
            assertTrue(uncaught.isEmpty(), "uncaught exceptions: $uncaught")
            return result
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun toolCall(fixture: Fixture, token: String): Response = fixture.post(
        modernRequest("tools/call", """"name":"media_library__list_playlists","arguments":{}"""),
        token,
        modernHeaders("tools/call", "media_library__list_playlists"),
    )

    // ---- 1. Crash safety ------------------------------------------------------------------------------------------

    @Test
    fun `a pairing store that throws fails that request, not the worker`() = noUncaught {
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val inner = InMemoryPairingStore()
        val store = object : PairingStore {
            override fun find(tokenHash: ByteArray): Pairing? {
                if (broken.get()) throw IllegalStateException("database is locked")
                return inner.find(tokenHash)
            }
        }
        Fixture(customStore = store).use { fixture ->
            val issued = PairingTokens.generate()
            inner.add(Pairing("p1", "agent"), issued.hash)

            val failed = fixture.post(ping, issued.token, pingHeaders)
            broken.set(false)
            val recovered = fixture.post(ping, issued.token, pingHeaders)

            assertEquals(500, failed.status)
            assertEquals(-32603, failed.errorCode)
            assertFalse(failed.body.contains("locked"), "internals must not leak: ${failed.body}")
            assertEquals(200, recovered.status)
        }
    }

    @Test
    fun `a log that throws never breaks serving`() = noUncaught {
        val calls = AtomicInteger()
        Fixture(config = { testConfig(log = { calls.incrementAndGet(); throw IllegalStateException("log sink failed") }) }).use { fixture ->
            val agent = fixture.pair()

            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
            assertEquals(401, fixture.post(ping, null, pingHeaders).status)
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
            assertTrue(calls.get() > 0, "the log was exercised")
        }
    }

    @Test
    fun `hooks that throw Errors are swallowed too`() = noUncaught {
        val issued = PairingTokens.generate()
        val store = InMemoryPairingStore().apply { add(Pairing("p1", "agent"), issued.hash) }
        Fixture(customStore = store, onRequestHook = { throw LinkageError("hook") }).use { fixture ->
            assertEquals(200, fixture.post(ping, issued.token, pingHeaders).status)
        }
    }

    @Test
    fun `a connection that cannot be handed to a worker is dropped, its slots freed, and the next one served`() = noUncaught {
        Fixture(config = { testConfig(maxConnections = 1, maxConnectionsPerAddress = 1) }).use { fixture ->
            val agent = fixture.pair()
            val failures = AtomicInteger(1)
            fixture.server.handOverFault = { if (failures.getAndDecrement() > 0) throw OutOfMemoryError("unable to create native thread") }

            val lost = runCatching { fixture.post(ping, agent.token, pingHeaders) }

            // The peer sees the connection close (no answer, or a reset); the server is still alive.
            assertTrue(lost.isFailure || lost.getOrThrow().body.isEmpty() || lost.getOrThrow().status != 200)
            // Both the global and the per-address slot were given back, or this would be a 503.
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
        }
    }

    @Test
    fun `stopping while requests are in flight neither throws nor leaks the port`() = noUncaught {
        val fixture = Fixture(config = { testConfig(maxConnections = 4) })
        val agent = fixture.pair()
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        val clients = List(6) {
            Thread {
                while (!stopping.get()) {
                    try {
                        fixture.post(ping, agent.token, pingHeaders)
                    } catch (_: Exception) {
                        // Connection refused, reset or cut: expected while stopping.
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(150)

        val started = System.nanoTime()
        fixture.server.stop()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        stopping.set(true)
        clients.forEach { it.join(5_000) }

        assertTrue(tookMs < 4_000, "stop took $tookMs ms")
        assertTrue(clients.none { it.isAlive })
        ServerSocket(fixture.port, 1, loopback).close()
    }

    // ---- 2. Pre-auth slot exhaustion ------------------------------------------------------------------------------

    @Test
    fun `a client that is slow to send its head is dropped at the head deadline`() {
        Fixture(config = { testConfig(headTimeout = 300.milliseconds, readTimeout = 30.seconds) }).use { fixture ->
            fixture.connect().use { socket ->
                socket.soTimeout = 5_000
                val started = System.nanoTime()
                socket.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Slow: ".toByteArray())
                socket.getOutputStream().flush()

                assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs in 200..3_000, "dropped after $elapsedMs ms")
            }
        }
    }

    @Test
    fun `once the token is verified the body gets the longer read timeout`() {
        Fixture(config = { testConfig(headTimeout = 300.milliseconds, readTimeout = 5.seconds) }).use { fixture ->
            val agent = fixture.pair()
            fixture.connect().use { socket ->
                socket.soTimeout = 5_000
                val body = ping.toByteArray()
                val head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer ${agent.token}\r\n" +
                    "MCP-Protocol-Version: $MODERN\r\nMcp-Method: ping\r\nContent-Length: ${body.size}\r\n\r\n"
                socket.getOutputStream().write(head.toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(700) // longer than the head deadline
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()

                val response = Fixture.parseResponse(socket.getInputStream().readBytes())

                assertEquals(200, response.status)
            }
        }
    }

    @Test
    fun `a body that never arrives is dropped at the read timeout`() {
        Fixture(config = { testConfig(headTimeout = 5.seconds, readTimeout = 300.milliseconds) }).use { fixture ->
            val agent = fixture.pair()
            fixture.connect().use { socket ->
                socket.soTimeout = 5_000
                val head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer ${agent.token}\r\n" +
                    "Content-Length: 100\r\n\r\n{"
                val started = System.nanoTime()
                socket.getOutputStream().write(head.toByteArray())
                socket.getOutputStream().flush()

                assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs in 200..3_000, "dropped after $elapsedMs ms")
            }
        }
    }

    @Test
    fun `a third concurrent connection from one address is refused while another address gets through`() {
        Fixture(config = { testConfig(maxConnectionsPerAddress = 2, headTimeout = 10.seconds) }).use { fixture ->
            val agent = fixture.pair()
            val other = InetAddress.getByName("127.0.0.2")
            assumeTrue(runCatching { fixture.connect(other).close() }.isSuccess, "127.0.0.2 is not usable as a source address here")
            val hold1 = fixture.connect().apply { getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray()) }
            val hold2 = fixture.connect().apply { getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray()) }
            try {
                val third = fixture.send(token = agent.token, body = ping.toByteArray(), headers = pingHeaders)
                val elsewhere = fixture.exchange(
                    ("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAuthorization: Bearer ${agent.token}\r\n" +
                        "MCP-Protocol-Version: $MODERN\r\nMcp-Method: ping\r\nContent-Length: ${ping.length}\r\n\r\n$ping").toByteArray(),
                    from = other,
                )

                assertEquals(503, third.status)
                assertEquals("1", third.headers["retry-after"])
                assertEquals(200, elsewhere.status)

                hold1.close()
                var status = 0
                repeat(50) {
                    if (status != 200) {
                        status = fixture.send(token = agent.token, body = ping.toByteArray(), headers = pingHeaders).status
                        if (status != 200) Thread.sleep(50)
                    }
                }
                assertEquals(200, status, "a freed slot is usable again")
            } finally {
                hold1.close()
                hold2.close()
            }
        }
    }

    // ---- 3. Host ----------------------------------------------------------------------------------------------------

    @Test
    fun `a foreign Host is 421 before the token is looked at, and is reported`() {
        Fixture().use { fixture ->
            val agent = fixture.pair()

            val withToken = fixture.post(ping, agent.token, pingHeaders + ("Host" to "evil.example:${fixture.port}"))
            val withoutToken = fixture.post(ping, null, pingHeaders + ("Host" to "localhost"))
            val wrongPort = fixture.post(ping, agent.token, pingHeaders + ("Host" to "127.0.0.1:1"))

            assertEquals(listOf(421, 421, 421), listOf(withToken, withoutToken, wrongPort).map { it.status })
            assertEquals(List(3) { DenialReason.FOREIGN_HOST }, fixture.denials.map { it.reason })
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `the bound address is accepted as Host with or without the port`() {
        Fixture().use { fixture ->
            val agent = fixture.pair()

            for (host in listOf("127.0.0.1", "127.0.0.1:${fixture.port}")) {
                assertEquals(200, fixture.post(ping, agent.token, pingHeaders + ("Host" to host)).status, host)
            }
        }
    }

    @Test
    fun `a missing Host is 400 on HTTP 1 1 and tolerated on HTTP 1 0`() {
        Fixture().use { fixture ->
            val agent = fixture.pair()

            val modern = fixture.send(token = agent.token, body = ping.toByteArray(), headers = pingHeaders + ("Host" to null))
            val legacy = fixture.send(
                requestLine = "POST /mcp HTTP/1.0",
                token = agent.token,
                body = ping.toByteArray(),
                headers = pingHeaders + ("Host" to null),
            )

            assertEquals(400, modern.status)
            assertEquals(200, legacy.status)
        }
    }

    @Test
    fun `a MagicDNS name works once it is allowed`() {
        Fixture(config = { testConfig(allowedHosts = setOf("gt-7-pro.tail1234.ts.net")) }).use { fixture ->
            val agent = fixture.pair()

            for (host in listOf("gt-7-pro.tail1234.ts.net", "GT-7-PRO.tail1234.ts.net:${fixture.port}")) {
                assertEquals(200, fixture.post(ping, agent.token, pingHeaders + ("Host" to host)).status, host)
            }
            assertEquals(421, fixture.post(ping, agent.token, pingHeaders + ("Host" to "other.tail1234.ts.net")).status)
        }
    }

    // ---- 4. Revocation and sessions ---------------------------------------------------------------------------------

    private class SwitchStore : PairingStore {
        @Volatile
        var current: Pair<Pairing, ByteArray>? = null

        override fun find(tokenHash: ByteArray): Pairing? =
            current?.takeIf { MessageDigest.isEqual(it.second, tokenHash) }?.first
    }

    private val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    private val legacyHeaders = mapOf("MCP-Protocol-Version" to "2025-11-25")

    @Test
    fun `a pairing re-created under the same id gets a fresh session, with or without an eviction`() {
        val store = SwitchStore()
        Fixture(customStore = store).use { fixture ->
            val first = PairingTokens.generate()
            store.current = Pairing("same-id", "agent") to first.hash
            fixture.post(legacyInitialize(), first.token)
            assertTrue(fixture.post(list, first.token, legacyHeaders).json().containsKey("result"))

            // Revoked and re-created without anyone evicting the old session.
            val second = PairingTokens.generate()
            store.current = Pairing("same-id", "agent") to second.hash
            assertEquals(401, fixture.post(list, first.token, legacyHeaders).status)
            assertEquals(-32602, fixture.post(list, second.token, legacyHeaders).errorCode, "no handshake inherited")
            assertEquals(1, fixture.server.sessionCount, "the old session was dropped, not kept beside the new one")

            fixture.post(legacyInitialize(), second.token)
            assertTrue(fixture.post(list, second.token, legacyHeaders).json().containsKey("result"))
            store.current = null
            fixture.server.evictSession("same-id")
            val third = PairingTokens.generate()
            store.current = Pairing("same-id", "agent") to third.hash
            assertEquals(-32602, fixture.post(list, third.token, legacyHeaders).errorCode)
        }
    }

    @Test
    fun `a request in flight at revocation cannot bring the old session back`() {
        val store = SwitchStore()
        lateinit var server: McpHttpServer
        val fixture = Fixture(
            customStore = store,
            onRequestHook = {
                // The owner revokes while the request is between authentication and its session.
                store.current = null
                server.evictSession(it.id)
            },
        )
        server = fixture.server
        fixture.use {
            val issued = PairingTokens.generate()
            store.current = Pairing("gone", "agent") to issued.hash

            val response = fixture.post(legacyInitialize(), issued.token)

            assertEquals(401, response.status)
            assertEquals(0, fixture.server.sessionCount)
        }
    }

    @Test
    fun `eviction takes every session of that pairing and no other`() {
        Fixture().use { fixture ->
            val one = fixture.pair("one")
            val two = fixture.pair("two")
            fixture.post(legacyInitialize(), one.token)
            fixture.post(legacyInitialize(), two.token)
            assertEquals(2, fixture.server.sessionCount)

            fixture.server.evictSession(one.pairing.id)

            assertEquals(1, fixture.server.sessionCount)
            assertEquals(-32602, fixture.post(list, one.token, legacyHeaders).errorCode)
            assertTrue(fixture.post(list, two.token, legacyHeaders).json().containsKey("result"))
        }
    }

    // ---- 5. Request timeout against a blocking node ------------------------------------------------------------------

    @Test
    fun `a handler that blocks past the request timeout is answered 504 and its slot is freed`() {
        val fixture = Fixture(
            config = { testConfig(requestTimeout = 300.milliseconds, maxConnections = 1, maxConnectionsPerAddress = 1) },
            beforeList = { Thread.sleep(4_000) }, // blocks the thread and never checks for cancellation
        )
        fixture.use {
            val agent = fixture.pair(grant = setOf(MediaLibrary.ReadScope))

            val started = System.nanoTime()
            val slow = toolCall(fixture, agent.token)
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(504, slow.status)
            assertEquals(-32603, slow.errorCode)
            assertTrue(tookMs < 3_000, "answered after $tookMs ms")
            // With one slot in total, this only works because the 504 released it.
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
        }
    }

    @Test
    fun `handlers that ignore cancellation use up the handler pool, tool calls are 503 and other methods keep working`() {
        val release = java.util.concurrent.atomic.AtomicBoolean(false)
        val fixture = Fixture(
            config = { testConfig(requestTimeout = 300.milliseconds, maxConnections = 1, maxConnectionsPerAddress = 1) },
            // Neither interruptible nor cooperative: only the flag ends it.
            beforeList = { while (!release.get()) Thread.onSpinWait() },
        )
        fixture.use {
            val agent = fixture.pair(grant = setOf(MediaLibrary.ReadScope))
            try {
                assertEquals(504, toolCall(fixture, agent.token).status)

                assertEquals(503, toolCall(fixture, agent.token).status)
                assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
                assertEquals(200, fixture.post(modernRequest("tools/list"), agent.token, modernHeaders("tools/list")).status)
            } finally {
                release.set(true)
            }
            var status = 0
            repeat(50) {
                if (status != 200) {
                    status = toolCall(fixture, agent.token).status
                    if (status != 200) Thread.sleep(50)
                }
            }
            assertEquals(200, status, "the pool recovers when the handler finally returns")
        }
    }

    @Test
    fun `a handler that throws an Error is a 500 with a generic JSON-RPC error`() = noUncaught {
        Fixture(beforeList = { throw LinkageError("secret internals") }).use { fixture ->
            val agent = fixture.pair(grant = setOf(MediaLibrary.ReadScope))

            val response = toolCall(fixture, agent.token)

            assertEquals(500, response.status)
            assertEquals(-32603, response.errorCode)
            assertFalse(response.body.contains("secret"), response.body)
            assertEquals(200, fixture.post(ping, agent.token, pingHeaders).status)
        }
    }

    @Test
    fun `a handler that throws an Exception is the session's tool error, not an HTTP failure`() {
        Fixture(beforeList = { throw IllegalStateException("secret internals") }).use { fixture ->
            val agent = fixture.pair(grant = setOf(MediaLibrary.ReadScope))

            val response = toolCall(fixture, agent.token)

            assertEquals(200, response.status)
            assertFalse(response.body.contains("secret"), response.body)
        }
    }

    // ---- 7. Throttled denials ---------------------------------------------------------------------------------------

    @Test
    fun `a flood of refused requests reaches the app once per window with the count of the rest`() {
        Fixture(config = { testConfig(deniedThrottle = 400.milliseconds) }).use { fixture ->
            repeat(5) { fixture.post(ping, null, pingHeaders) }
            assertEquals(listOf(0), fixture.denials.map { it.suppressed })

            fixture.post(ping, "not-a-known-token", pingHeaders)
            assertEquals(listOf(DenialReason.MISSING_TOKEN, DenialReason.INVALID_TOKEN), fixture.denials.map { it.reason }, "reasons throttle separately")

            Thread.sleep(500)
            fixture.post(ping, null, pingHeaders)

            assertEquals(3, fixture.denials.size)
            assertEquals(DenialReason.MISSING_TOKEN, fixture.denials.last().reason)
            assertEquals(4, fixture.denials.last().suppressed)
        }
    }

    // ---- 8. Which address may be bound ------------------------------------------------------------------------------

    @Test
    fun `an address outside loopback and the Tailscale ranges is refused unless explicitly allowed`() {
        Fixture().use { fixture ->
            fixture.server.stop()

            for (text in listOf("192.168.1.5", "10.0.0.1", "100.63.255.255", "100.128.0.1", "fd7a:115c:a1e1::1", "fe80::1", "8.8.8.8")) {
                val error = assertThrows<IllegalArgumentException>(text) { fixture.server.start(InetAddress.getByName(text), 0) }
                assertTrue(error.message!!.contains("Tailscale"), error.message)
            }
            assertFalse(fixture.server.isRunning)
        }
    }

    @Test
    fun `Tailscale addresses pass the check, so a failure to bind them is an IOException, not a refusal`() {
        Fixture().use { fixture ->
            fixture.server.stop()

            // Not addresses of this machine (192.0.2.0/24 is for documentation, 100.64.0.1 is not assigned here),
            // so binding fails at the socket, which proves the address check let them through.
            for (text in listOf("100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1")) {
                assertThrows<IOException>(text) { fixture.server.start(InetAddress.getByName(text), 0) }
            }
            assertFalse(fixture.server.isRunning)
        }
    }
}
