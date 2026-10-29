package eus.bihurri.kora.mcp.http

import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.core.AuditEntry
import eus.bihurri.kora.core.AuditLog
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.DefaultCallerPolicy
import eus.bihurri.kora.core.ExternalAccess
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.testing.provideStubbed
import eus.bihurri.kora.mcp.McpServerInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Master switch and per-caller grants controlled by the test. */
class TestAccess : ExternalAccess {
    val grants = java.util.concurrent.ConcurrentHashMap<Caller, Set<Scope>>()

    override suspend fun isEnabled(): Boolean = true

    override suspend fun grantedScopes(caller: Caller): Set<Scope> = grants[caller].orEmpty()
}

class TestAudit : AuditLog {
    val entries = CopyOnWriteArrayList<AuditEntry>()

    override fun record(entry: AuditEntry) {
        entries += entry
    }
}

class Response(val status: Int, val headers: Map<String, String>, val body: String) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    val errorCode: Int get() = json()["error"]!!.jsonObject["code"].toString().toInt()
}

class Paired(val pairing: Pairing, val token: String) {
    val caller = Caller(eus.bihurri.kora.core.CallerKind.REMOTE_AGENT, "tailnet:${pairing.id}")
}

/** The server defaults, except what makes tests flaky (a per-address cap of 2 trips on back-to-back connections). */
fun testConfig(
    path: String = McpHttpConfig.DEFAULT_PATH,
    maxBodyBytes: Int = McpHttpConfig.DEFAULT_MAX_BODY_BYTES,
    allowedOrigins: Set<String> = emptySet(),
    allowedHosts: Set<String> = emptySet(),
    maxConnections: Int = 8,
    maxConnectionsPerAddress: Int = 64,
    headTimeout: kotlin.time.Duration = 3.seconds,
    readTimeout: kotlin.time.Duration = 10.seconds,
    requestTimeout: kotlin.time.Duration = 30.seconds,
    writeTimeout: kotlin.time.Duration = 10.seconds,
    deniedThrottle: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    allowNonTailnetAddress: Boolean = false,
    log: (String) -> Unit = {},
) = McpHttpConfig(
    path, maxBodyBytes, allowedOrigins, allowedHosts, maxConnections, maxConnectionsPerAddress, headTimeout,
    readTimeout, requestTimeout, writeTimeout, deniedThrottle, allowNonTailnetAddress, log,
)

val playlists = List(3) { PlaylistSummary("pl-$it", "Playlist $it", it + 1) }

/** A node, a store, a listening [McpHttpServer] on 127.0.0.1 and a raw HTTP client for it. */
class Fixture(
    config: (McpHttpConfig) -> McpHttpConfig = { it },
    /** Runs inside the handler of `list_playlists`, so a test can make the node slow, blocking or failing. */
    beforeList: suspend () -> Unit = {},
    /** A store of the test's own; [store] is still where [pair] registers pairings. */
    customStore: PairingStore? = null,
    /** Runs in the server's `onRequest` hook, after the token was verified. */
    private val onRequestHook: (Pairing) -> Unit = {},
) : AutoCloseable {
    val access = TestAccess()
    val audit = TestAudit()
    val store = InMemoryPairingStore()
    val logs = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<Pairing>()
    val denials = CopyOnWriteArrayList<DeniedAttempt>()

    val node = KoraNode(nodeId = "kardio", policy = DefaultCallerPolicy(access), audit = audit).apply {
        provideStubbed(MediaLibrary) {
            on(MediaLibrary.ListPlaylists) { page ->
                beforeList()
                Page(playlists.drop(page.offset).take(page.limit), playlists.size, page.offset)
            }
        }
    }

    private val listener = object : McpHttpListener {
        override fun onRequest(pairing: Pairing) {
            requests += pairing
            onRequestHook(pairing)
        }

        override fun onDenied(attempt: DeniedAttempt) {
            denials += attempt
        }
    }

    val server = McpHttpServer(
        node = node,
        serverInfo = McpServerInfo("kardio-test", "1.0"),
        pairings = customStore ?: store,
        config = config(testConfig(log = { logs += it })),
        listener = listener,
    )
    val port: Int = server.start(InetAddress.getLoopbackAddress(), 0)

    fun pair(name: String = "claude", grant: Set<Scope> = emptySet()): Paired {
        val issued = PairingTokens.generate()
        val pairing = Pairing(PairingTokens.generateId(), name, createdAtEpochMs = 1)
        store.add(pairing, issued.hash)
        return Paired(pairing, issued.token).also { if (grant.isNotEmpty()) access.grants[it.caller] = grant }
    }

    override fun close() = server.stop()

    /**
     * Sends one request over a fresh connection and reads the answer until the server closes it. [headers] override
     * the defaults; a null value removes one. `Content-Length` follows [body] unless overridden.
     */
    fun send(
        method: String = "POST",
        path: String = "/mcp",
        body: ByteArray? = null,
        token: String? = null,
        headers: Map<String, String?> = emptyMap(),
        requestLine: String? = null,
    ): Response {
        val all = linkedMapOf<String, String?>(
            "Host" to "127.0.0.1:$port",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream",
        )
        token?.let { all["Authorization"] = "Bearer $it" }
        if (body != null) all["Content-Length"] = body.size.toString()
        for ((name, value) in headers) {
            all.keys.removeAll { it.equals(name, ignoreCase = true) }
            if (value != null) all[name] = value
        }
        val head = buildString {
            append(requestLine ?: "$method $path HTTP/1.1").append("\r\n")
            all.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        return exchange(head.toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0)))
    }

    fun post(body: String, token: String?, headers: Map<String, String?> = emptyMap()): Response =
        send(body = body.toByteArray(), token = token, headers = headers)

    /** Writes [bytes] as they are and parses the answer. */
    fun exchange(bytes: ByteArray, from: InetAddress? = null): Response {
        connect(from).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
            val received = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                received.write(buffer, 0, n)
            }
            return parseResponse(received.toByteArray())
        }
    }

    /** A connection to the server, from [from] when given (any 127.x.x.x address is local on Linux). */
    fun connect(from: InetAddress? = null): Socket {
        val socket = Socket()
        if (from != null) socket.bind(java.net.InetSocketAddress(from, 0))
        socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        return socket
    }

    companion object {
        fun parseResponse(raw: ByteArray): Response {
            var text = String(raw, Charsets.ISO_8859_1)
            // Skip interim responses such as 100 Continue.
            while (text.startsWith("HTTP/1.1 1")) text = text.substringAfter("\r\n\r\n")
            val head = text.substringBefore("\r\n\r\n")
            val body = text.substringAfter("\r\n\r\n", "")
            val lines = head.split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(status, headers, String(body.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
        }
    }
}

fun legacyInitialize(id: Int = 1, version: String = "2025-11-25") =
    """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

const val MODERN = "2026-07-28"

fun modernMeta(version: String = MODERN) =
    """{"io.modelcontextprotocol/protocolVersion":"$version","io.modelcontextprotocol/clientInfo":{"name":"t","version":"1"},"io.modelcontextprotocol/clientCapabilities":{}}"""

fun modernRequest(method: String, params: String = "", id: Int = 1, version: String = MODERN): String {
    val extra = if (params.isEmpty()) "" else ",$params"
    return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":{"_meta":${modernMeta(version)}$extra}}"""
}

/** Headers a stateless-era client sends for [method] (and, for `tools/call`, [name]). */
fun modernHeaders(method: String, name: String? = null, version: String = MODERN): Map<String, String?> =
    mapOf("MCP-Protocol-Version" to version, "Mcp-Method" to method, "Mcp-Name" to name)
