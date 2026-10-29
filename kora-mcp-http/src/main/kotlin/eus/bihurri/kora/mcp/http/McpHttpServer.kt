package eus.bihurri.kora.mcp.http

import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.JsonGate
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.mcp.McpProtocol
import eus.bihurri.kora.mcp.McpServerInfo
import eus.bihurri.kora.mcp.McpSession
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.Future
import java.util.concurrent.ExecutionException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves a [KoraNode] over MCP **Streamable HTTP** (the `POST` half of it; see the README for the exact subset):
 * one endpoint, one JSON-RPC message per request, an `application/json` answer, no streaming. Pure JVM on
 * `java.net.ServerSocket`, so it runs on Android too.
 *
 * ## Who may talk to it
 * Every request must carry `Authorization: Bearer <token>` of a pairing in [pairings]; the token is hashed with
 * SHA-256 and looked up ([PairingStore]), so a revoked pairing is refused on its very next request. Each pairing is
 * a caller of its own, `Caller(REMOTE_AGENT, "tailnet:<pairing id>")`, built here from the verified pairing and
 * never from the payload: the node's policy and grants apply per pairing exactly as they do for the adb gate. There
 * is one [McpSession] per pairing, created on its first request.
 *
 * Before the token, two checks keep browsers out: a request with an `Origin` header is refused with `403` unless the
 * origin is in [McpHttpConfig.allowedOrigins], and a `Host` that is not this server's address (or an
 * [McpHttpConfig.allowedHosts] name) is `421`. Both are defences against DNS rebinding. Every other unauthenticated
 * request gets the same `401`, whatever it asks for, so nothing can be probed without a token.
 *
 * This class does **not** choose where it listens. The caller passes the address to [start]: the security model of
 * the feature relies on it being the address of the tailnet interface only. The wildcard is refused, and so is any
 * address outside loopback and Tailscale's ranges unless [McpHttpConfig.allowNonTailnetAddress] says otherwise.
 *
 * ## Sessions
 * No `Mcp-Session-Id` is ever issued (the specification makes it optional, and `2026-07-28` removed it); one sent
 * by a client is ignored. A legacy client's handshake state lives in the pairing's [McpSession], so two clients
 * sharing one pairing share it, as two adb clients share the `shell` session. Give each agent its own pairing.
 * Sessions are keyed by pairing id **and** token hash, and created only while the store still knows the token, so a
 * pairing that is revoked and later re-created under the same id starts with a fresh session, and a request that
 * was already in flight at revocation cannot bring the old one back.
 *
 * ## Threads and failure
 * One acceptor thread, up to [McpHttpConfig.maxConnections] worker threads and as many handler threads (all daemon).
 * Each connection serves one request and closes. Watchdogs close connections that are too slow to send their head
 * ([McpHttpConfig.headTimeout]), their body ([McpHttpConfig.readTimeout]) or to read the answer
 * ([McpHttpConfig.writeTimeout]); one remote address holds at most [McpHttpConfig.maxConnectionsPerAddress].
 * Nothing that happens while serving a connection escapes its thread: on Android an exception that leaves an
 * executor task kills the app, so every worker body, hook, log call and store call is guarded, and a failure ends
 * only that connection (the `log` receives a generic line).
 *
 * A `tools/call` runs app code on a handler thread, not on the worker, so that [McpHttpConfig.requestTimeout] holds
 * even for a handler that blocks and ignores cancellation: the client gets `504` and the connection and its slot are
 * freed on time; the handler thread is interrupted and goes back to the pool when the handler returns. The handler
 * pool is bounded, so handlers that never return can only cost `maxConnections` threads, after which tool calls are
 * `503` until one returns. Other MCP methods do not use it and keep working.
 *
 * @param node the node whose capabilities become tools.
 * @param serverInfo identity reported to MCP clients.
 * @param pairings where tokens are checked.
 * @param gate how tools are called; the default forwards to peers under the node's policy. Pass your own to set
 *   timeouts or size limits.
 */
public class McpHttpServer(
    private val node: KoraNode,
    private val serverInfo: McpServerInfo,
    private val pairings: PairingStore,
    private val config: McpHttpConfig = McpHttpConfig(),
    private val listener: McpHttpListener = McpHttpListener,
    private val gate: JsonGate = JsonGate(node, forwardToPeers = true),
) : Closeable {
    private class Running(
        val socket: ServerSocket,
        val address: InetAddress,
        val workers: ThreadPoolExecutor,
        val handlers: ThreadPoolExecutor,
        val watchdog: ScheduledThreadPoolExecutor,
        val permits: Semaphore,
        val perAddress: PerAddressLimiter,
        val hosts: HostPolicy,
        val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet(),
    ) {
        lateinit var acceptor: Thread
    }

    private class SessionKey(val pairingId: String, val tokenHash: String) {
        override fun equals(other: Any?) = other is SessionKey && other.pairingId == pairingId && other.tokenHash == tokenHash

        override fun hashCode() = 31 * pairingId.hashCode() + tokenHash.hashCode()
    }

    /** A request whose token was verified: who, and the hash that proved it. */
    private class Authenticated(val pairing: Pairing, val tokenHash: ByteArray)

    private val lifecycle = Any()
    private val sessionLock = Any()

    @Volatile
    private var running: Running? = null

    private val sessions = ConcurrentHashMap<SessionKey, McpSession>()
    private val denials = DenialThrottle(config.deniedThrottle.inWholeNanoseconds)

    /** Test seam: runs just before a connection is handed to a worker. */
    @Volatile
    internal var handOverFault: (() -> Unit)? = null

    /** MCP sessions alive now (test seam). */
    internal val sessionCount: Int get() = sessions.size

    /** Whether the server is listening now. */
    public val isRunning: Boolean get() = running != null

    /** The port it listens on, or null when stopped. Useful after `start(address, 0)`. */
    public val port: Int? get() = running?.socket?.localPort

    /**
     * Starts listening on [address] and [port] (0 picks a free one) and returns the port. Binds that address only:
     * for the tailnet that is the address of the Tailscale interface, never the wildcard (refused with
     * [IllegalArgumentException]). An address that is neither loopback nor in Tailscale's ranges (100.64.0.0/10,
     * fd7a:115c:a1e0::/48) is refused too, unless [McpHttpConfig.allowNonTailnetAddress] is set. 100.64.0.0/10 is
     * shared with carrier-grade NAT, so this is a guard against typos, not proof that [address] is the Tailscale
     * interface: pick the address of that interface specifically. Can be called again after [stop], for instance when
     * the address changed.
     *
     * @throws IllegalStateException when already running.
     * @throws IOException when the address cannot be bound.
     */
    public fun start(address: InetAddress, port: Int = 0): Int = synchronized(lifecycle) {
        check(running == null) { "Already running on port ${this.port}" }
        require(!address.isAnyLocalAddress) { "Bind to one specific address, not the wildcard" }
        require(config.allowNonTailnetAddress || TailnetAddresses.isAllowed(address)) {
            "${address.hostAddress} is not a loopback or Tailscale address (100.64.0.0/10, fd7a:115c:a1e0::/48)"
        }
        require(port in 0..65535) { "Invalid port: $port" }
        val socket = ServerSocket(port, BACKLOG, address)
        val bound = socket.localPort
        val state = try {
            Running(
                socket = socket,
                address = address,
                workers = Executors.newCachedThreadPool(daemonThreads("kora-mcp-http-worker")) as ThreadPoolExecutor,
                handlers = ThreadPoolExecutor(
                    0, config.maxConnections, HANDLER_KEEP_ALIVE_S, TimeUnit.SECONDS, SynchronousQueue(),
                    daemonThreads("kora-mcp-http-handler"),
                ),
                watchdog = ScheduledThreadPoolExecutor(1, daemonThreads("kora-mcp-http-watchdog")).apply {
                    removeOnCancelPolicy = true
                },
                permits = Semaphore(config.maxConnections),
                perAddress = PerAddressLimiter(config.maxConnectionsPerAddress),
                hosts = HostPolicy(address, bound, config.allowedHosts),
            )
        } catch (t: Throwable) {
            socket.closeQuietly()
            throw t
        }
        state.acceptor = Thread({ acceptLoop(state) }, "kora-mcp-http-acceptor").apply { isDaemon = true }
        running = state
        state.acceptor.start()
        log("MCP HTTP listening on ${address.hostAddress}:$bound${config.path}")
        bound
    }

    /**
     * Stops listening, drops the connections in flight and frees the port. Idempotent. Pairings and their MCP
     * sessions survive, so the server can be started again.
     */
    public fun stop() {
        val state = synchronized(lifecycle) { running.also { running = null } } ?: return
        state.socket.closeQuietly()
        state.connections.forEach { it.closeQuietly() }
        state.workers.shutdownNow()
        state.handlers.shutdownNow()
        state.watchdog.shutdownNow()
        try {
            state.acceptor.join(STOP_WAIT_MS)
            state.workers.awaitTermination(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        log("MCP HTTP stopped")
    }

    override fun close(): Unit = stop()

    /**
     * Forgets the MCP sessions of [pairingId], for when the pairing was revoked: frees their memory and clears any
     * handshake state. Revocation itself needs only the [PairingStore] to stop returning the pairing; call this
     * right after, so that a request already in flight cannot find the session again.
     */
    public fun evictSession(pairingId: String) {
        synchronized(sessionLock) { sessions.keys.removeIf { it.pairingId == pairingId } }
    }

    // ---- Guarded calls into code that is not ours ---------------------------------------------------------------

    /** The `log` of the config must never break serving, whatever it throws. */
    private fun log(line: String) {
        try {
            config.log(line)
        } catch (_: Throwable) {
            // Nothing sensible left to do.
        }
    }

    private fun hook(action: () -> Unit) {
        try {
            action()
        } catch (_: Throwable) {
            // A broken hook must not stop the service.
        }
    }

    // ---- Connections -------------------------------------------------------------------------------------------

    private fun acceptLoop(state: Running) {
        try {
            while (!state.socket.isClosed) {
                var client: Socket? = null
                try {
                    client = state.socket.accept()
                    handOver(state, client)
                } catch (_: IOException) {
                    client?.closeQuietly()
                    if (state.socket.isClosed) return
                    // E.g. out of file descriptors: back off instead of spinning.
                    pause()
                } catch (_: InterruptedException) {
                    return
                } catch (t: Throwable) {
                    // Whatever it was (even an OutOfMemoryError while starting a thread), this connection is lost
                    // and the next one is served.
                    client?.closeQuietly()
                    log("Accept failed: ${t.javaClass.simpleName}")
                    pause()
                }
            }
        } catch (t: Throwable) {
            log("Acceptor stopped: ${t.javaClass.simpleName}")
        }
    }

    private fun pause() {
        try {
            Thread.sleep(ACCEPT_RETRY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Takes the slots a connection needs and gives it to a worker; on any failure, gives everything back. */
    private fun handOver(state: Running, client: Socket) {
        val address = (client.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
        if (!state.perAddress.tryAcquire(address)) return refuse(client, "Too many connections from this address")
        if (!state.permits.tryAcquire()) {
            state.perAddress.release(address)
            return refuse(client, "Too many connections")
        }
        state.connections += client
        try {
            handOverFault?.invoke()
            state.workers.execute {
                try {
                    serve(state, client, address)
                } catch (t: Throwable) {
                    // Nothing may leave a worker: on Android that would kill the app.
                    log("Connection failed: ${t.javaClass.simpleName}")
                } finally {
                    release(state, client, address)
                }
            }
        } catch (t: Throwable) {
            release(state, client, address)
            if (t !is RejectedExecutionException) log("Could not start a worker: ${t.javaClass.simpleName}")
        }
    }

    private fun release(state: Running, client: Socket, address: String) {
        state.connections -= client
        state.perAddress.release(address)
        state.permits.release()
        client.closeQuietly()
    }

    private fun refuse(client: Socket, message: String) {
        try {
            val body = errorBody(INVALID_REQUEST, message).toByteArray()
            HttpWire.write(client.getOutputStream(), 503, JSON_HEADERS + ("Retry-After" to "1"), body)
        } catch (_: Throwable) {
            // The peer is gone: nothing to tell.
        } finally {
            client.closeQuietly()
        }
    }

    private class Reply(
        val status: Int,
        val body: String? = null,
        val headers: List<Pair<String, String>> = emptyList(),
        val pairingId: String? = null,
    )

    /** Whether the request body was consumed: if not, the connection is drained before closing (see [drain]). */
    private class Progress {
        @Volatile
        var bodyRead = false
    }

    /** The one watchdog a connection has at a time: head, then body, then (separately) the write. */
    private class Deadline(private val state: Running, private val client: Socket, private val onFire: (Socket) -> Unit) {
        private var future: ScheduledFuture<*>? = null

        @Synchronized
        fun start(millis: Long) {
            future?.cancel(false)
            future = try {
                state.watchdog.schedule({ onFire(client) }, millis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Stopping: the connection is closed by stop() anyway.
                onFire(client)
                throw IOException("Stopping")
            }
        }

        @Synchronized
        fun cancel() {
            future?.cancel(false)
            future = null
        }
    }

    private fun serve(state: Running, client: Socket, remote: String) {
        var method = "-"
        var path = "-"
        val progress = Progress()
        val deadline = Deadline(state, client) { it.closeQuietly() }
        val reply = try {
            client.tcpNoDelay = true
            deadline.start(config.headTimeout.inWholeMilliseconds)
            val input = BufferedInputStream(client.getInputStream())
            val request = HttpWire.readHead(input)
            method = request.method
            path = if (request.target.substringBefore('?') == config.path) config.path else "<other>"
            exchange(state, request, input, client.getOutputStream(), remote, deadline, progress)
        } catch (e: HttpException) {
            fail(e.status, if (e.status >= 500) INTERNAL_ERROR else INVALID_REQUEST, e.message.orEmpty())
        } catch (_: ConnectionClosed) {
            return
        } catch (_: IOException) {
            // Cut by a watchdog, by stop(), or by the peer.
            return
        } catch (_: InterruptedException) {
            return
        } catch (t: Throwable) {
            log("Request failed: ${t.javaClass.simpleName}")
            fail(500, INTERNAL_ERROR, "Internal error")
        } finally {
            deadline.cancel()
        }
        val writing = Deadline(state, client) { it.closeQuietly() }
        try {
            writing.start(config.writeTimeout.inWholeMilliseconds)
            val body = reply.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val headers = if (reply.body != null) JSON_HEADERS + reply.headers else NO_STORE_HEADERS + reply.headers
            HttpWire.write(client.getOutputStream(), reply.status, headers, body)
            if (!progress.bodyRead) drain(client)
        } catch (_: IOException) {
            // The peer stopped reading; the connection is closed by the caller.
        } finally {
            writing.cancel()
        }
        log("$method $path -> ${reply.status} pairing=${reply.pairingId ?: "-"}")
    }

    /**
     * Lingering close for a request refused before its body was read: tell the peer we are done sending, then
     * swallow what it still sends for a moment. Closing a socket with unread data makes TCP reset the connection,
     * and a reset can destroy the response before the client reads it (a `401` or `413` would look like a network
     * error). Bounded in bytes and in time, so it cannot be used to hold a worker.
     */
    private fun drain(client: Socket) {
        client.shutdownOutput()
        val deadline = System.nanoTime() + DRAIN_MS * 1_000_000
        val scratch = ByteArray(4096)
        var total = 0
        client.soTimeout = DRAIN_MS.toInt()
        try {
            while (total < DRAIN_MAX_BYTES && System.nanoTime() < deadline) {
                val n = client.getInputStream().read(scratch)
                if (n < 0) return
                total += n
            }
        } catch (_: IOException) {
            // Timed out or reset: done either way.
        }
    }

    // ---- One request -------------------------------------------------------------------------------------------

    /**
     * Checks run cheapest and least revealing first. Before the token only `Origin` and `Host` can refuse a request
     * with something other than `401`; nothing is read from the body until the caller is known.
     */
    private fun exchange(
        state: Running,
        request: HttpRequest,
        input: InputStream,
        out: OutputStream,
        remote: String,
        deadline: Deadline,
        progress: Progress,
    ): Reply {
        val origin = request.single("origin")
        if (origin != null && config.allowedOrigins.none { it.equals(origin, ignoreCase = true) }) {
            denied(DenialReason.FOREIGN_ORIGIN, remote)
            return fail(403, INVALID_REQUEST, "Origin not allowed")
        }
        val host = request.single("host")
        if (host == null) {
            if (request.version == "HTTP/1.1") return fail(400, INVALID_REQUEST, "Host header is required")
        } else if (!state.hosts.accepts(host)) {
            denied(DenialReason.FOREIGN_HOST, remote)
            return fail(421, INVALID_REQUEST, "Host not allowed")
        }

        val auth = authenticate(request, remote) ?: return unauthorized(request)
        val pairing = auth.pairing
        // The caller is known: the body gets its own, longer deadline.
        deadline.start(config.readTimeout.inWholeMilliseconds)
        hook { listener.onRequest(pairing) }
        fun fail(status: Int, code: Int, message: String, headers: List<Pair<String, String>> = emptyList()) =
            fail(status, code, message, headers = headers, pairingId = pairing.id)

        if (request.target.substringBefore('?') != config.path) return fail(404, INVALID_REQUEST, "Not found")
        if (request.method != "POST") return fail(405, INVALID_REQUEST, "Use POST", headers = listOf("Allow" to "POST"))
        if (!isJson(request.single("content-type"))) return fail(415, INVALID_REQUEST, "Content-Type must be application/json")
        if (!acceptsJson(request.joined("accept"))) return fail(406, INVALID_REQUEST, "Accept must allow application/json")
        val expect = request.single("expect")
        if (expect != null && !expect.equals("100-continue", ignoreCase = true)) return fail(417, INVALID_REQUEST, "Unsupported Expect")
        val length = HttpWire.contentLength(request)
        if (length > config.maxBodyBytes) return fail(413, INVALID_REQUEST, "Body exceeds ${config.maxBodyBytes} bytes")

        if (expect != null) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val bytes = HttpWire.readBody(input, length.toInt())
        progress.bodyRead = true
        deadline.cancel()
        val text = decodeUtf8(bytes) ?: return fail(400, PARSE_ERROR, "Body is not UTF-8")
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            return fail(400, PARSE_ERROR, "Parse error")
        }
        validateHeaders(request, root as? JsonObject)?.let { return it.withPairing(pairing.id) }

        val session = sessionFor(auth) ?: return unauthorized(request).withPairing(null)
        val answer = try {
            val method = ((root as? JsonObject)?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
            handle(state, session, text, isToolCall = method == "tools/call")
        } catch (e: HttpException) {
            return fail(e.status, INTERNAL_ERROR, e.message.orEmpty())
        }
        return if (answer == null) Reply(202, pairingId = pairing.id) else Reply(200, answer, pairingId = pairing.id)
    }

    /**
     * Answers [text] within [McpHttpConfig.requestTimeout]. A `tools/call` runs app code, which may block without
     * checking for cancellation, so it runs on a handler thread and the wait, not the handler, decides when the
     * connection is answered; the handler is interrupted and returns to the pool when it returns. Every other method
     * is answered by `McpSession` itself from memory and runs here, so a stuck tool never makes `ping`, `initialize`
     * or `tools/list` fail.
     */
    private fun handle(state: Running, session: McpSession, text: String, isToolCall: Boolean): String? {
        val millis = config.requestTimeout.inWholeMilliseconds
        if (!isToolCall) {
            return try {
                runBlocking { withTimeout(millis) { session.handle(text) } }
            } catch (_: TimeoutCancellationException) {
                throw HttpException(504, "The node did not answer in time")
            }
        }
        val task: Future<String?> = try {
            state.handlers.submit(Callable { runBlocking { session.handle(text) } })
        } catch (_: RejectedExecutionException) {
            throw HttpException(503, "Too many tool calls in progress")
        }
        try {
            return task.get(millis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            throw HttpException(504, "The node did not answer in time")
        } catch (e: InterruptedException) {
            task.cancel(true)
            throw e
        } catch (_: ExecutionException) {
            // `McpSession` turns every Exception into a JSON-RPC error itself; this is an Error or a bug.
            throw HttpException(500, "Internal error")
        }
    }

    /**
     * The session of a verified token, created on first use. The store is asked again under the lock that eviction
     * takes, so a pairing revoked (and evicted) while its request was in flight does not get its session back.
     */
    private fun sessionFor(auth: Authenticated): McpSession? {
        val key = SessionKey(auth.pairing.id, hex(auth.tokenHash))
        sessions[key]?.let { return it }
        return synchronized(sessionLock) {
            sessions[key] ?: run {
                if (lookUp(auth.tokenHash)?.id != auth.pairing.id) return@run null
                // A new token for this id (a pairing re-created after a revoke): the old session is not inherited.
                sessions.keys.removeIf { it.pairingId == key.pairingId }
                McpSession(node, Caller(CallerKind.REMOTE_AGENT, "$CALLER_PREFIX${key.pairingId}"), serverInfo, gate)
                    .also { sessions[key] = it }
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun Reply.withPairing(pairingId: String?) = Reply(status, body, headers, pairingId)

    private fun fail(
        status: Int,
        code: Int,
        message: String,
        data: JsonElement? = null,
        headers: List<Pair<String, String>> = emptyList(),
        pairingId: String? = null,
    ) = Reply(status, errorBody(code, message, data), headers, pairingId)

    private fun denied(reason: DenialReason, remote: String) {
        val suppressed = denials.admit(remote, reason) ?: return
        hook { listener.onDenied(DeniedAttempt(reason, remote, System.currentTimeMillis(), suppressed)) }
    }

    // ---- Authentication ----------------------------------------------------------------------------------------

    /** The store, called as if it could throw anything: a failing store fails the request, never the worker. */
    private fun lookUp(tokenHash: ByteArray): Pairing? =
        try {
            pairings.find(tokenHash)
        } catch (t: Throwable) {
            log("Pairing store failed: ${t.javaClass.simpleName}")
            throw HttpException(500, "Internal error")
        }

    /** The pairing behind a valid Bearer token; null when there is none (the denial is reported). */
    private fun authenticate(request: HttpRequest, remote: String): Authenticated? {
        val header = request.single("authorization")
        val token = header?.let(::bearerToken)
        if (token == null) {
            denied(DenialReason.MISSING_TOKEN, remote)
            return null
        }
        val hash = PairingTokens.hash(token)
        val found = if (token.isEmpty() || token.length > PairingTokens.MAX_TOKEN_CHARS || !TOKEN68.matches(token)) {
            null
        } else {
            lookUp(hash)
        }
        if (found == null) denied(DenialReason.INVALID_TOKEN, remote)
        return found?.let { Authenticated(it, hash) }
    }

    private fun bearerToken(header: String): String? {
        val space = header.indexOf(' ')
        if (space < 0 || !header.substring(0, space).equals("Bearer", ignoreCase = true)) return null
        return header.substring(space + 1).trim(' ')
    }

    private fun unauthorized(request: HttpRequest): Reply {
        val presented = runCatching { request.single("authorization") }.getOrNull()?.let(::bearerToken) != null
        val challenge = if (presented) "Bearer error=\"invalid_token\"" else "Bearer"
        return fail(401, INVALID_REQUEST, "Authentication required", headers = listOf("WWW-Authenticate" to challenge))
    }

    // ---- Content negotiation and MCP headers -------------------------------------------------------------------

    private fun isJson(contentType: String?): Boolean {
        if (contentType == null) return false
        val parts = contentType.split(';').map { it.trim() }
        if (!parts.first().equals("application/json", ignoreCase = true)) return false
        return parts.drop(1).all { parameter ->
            val (name, value) = parameter.split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim().trim('"') }
            !name.equals("charset", ignoreCase = true) || value.equals("utf-8", ignoreCase = true)
        }
    }

    /** RFC 9110 `Accept`: a missing header accepts anything; a range with `q=0` refuses. */
    private fun acceptsJson(accept: String?): Boolean {
        if (accept == null) return true
        return accept.split(',').any { range ->
            val parts = range.split(';').map { it.trim() }
            val type = parts.first().lowercase()
            val refused = parts.drop(1).any { p ->
                p.lowercase().replace(" ", "").let { it.startsWith("q=") && it.substring(2).toDoubleOrNull() == 0.0 }
            }
            !refused && (type == "application/json" || type == "application/*" || type == "*/*")
        }
    }

    /**
     * The checks the specification attaches to the MCP headers, against what the body says (the body is the source
     * of truth). Returns the refusal, or null when the request is consistent.
     *
     * - `MCP-Protocol-Version`: a value this server does not speak is `400` with `-32022`. A stateless-era request
     *   (its `_meta` carries `io.modelcontextprotocol/protocolVersion`) must send the header and it must equal the
     *   body's; otherwise `400` with `-32020` (`HeaderMismatch`). A legacy client (`2025-11-25`, `2025-06-18`) may
     *   send the header with its negotiated version, or omit it, notably on `initialize`.
     * - `Mcp-Method` (and `Mcp-Name` for `tools/call`): required on every stateless-era request and must equal
     *   `method` and `params.name`; a value in `=?base64?...?=` form is decoded first.
     */
    private fun validateHeaders(request: HttpRequest, root: JsonObject?): Reply? {
        val header = request.single("mcp-protocol-version")
        val params = root?.get("params") as? JsonObject
        val bodyVersion = ((params?.get("_meta") as? JsonObject)?.get(META_VERSION) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (header != null && header !in McpProtocol.SUPPORTED_VERSIONS) {
            val data = buildJsonObject {
                putJsonArray("supported") { McpProtocol.SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } }
                put("requested", header.take(MAX_ECHO))
            }
            return fail(400, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", data)
        }
        val modern = header in McpProtocol.MODERN_VERSIONS || bodyVersion != null
        if (!modern) return null
        if (header == null) return mismatch("MCP-Protocol-Version header is required")
        if (header != bodyVersion) return mismatch("MCP-Protocol-Version does not match _meta '$META_VERSION'")
        // A stateless-era body is an object with `_meta`, which the version comparison above proved.
        if ("id" !in checkNotNull(root)) return null
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (request.single("mcp-method") != method) return mismatch("Mcp-Method does not match the method of the body")
        if (method == "tools/call") {
            val name = (params.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val sent = request.single("mcp-name")?.let(::decodeHeaderValue)
            if (sent != name) return mismatch("Mcp-Name does not match params.name")
        }
        return null
    }

    private fun mismatch(message: String) = fail(400, HEADER_MISMATCH, "Header mismatch: $message")

    private fun decodeHeaderValue(value: String): String? {
        if (!value.startsWith("=?base64?") || !value.endsWith("?=") || value.length < 11) return value
        return try {
            decodeUtf8(Base64.getDecoder().decode(value.substring(9, value.length - 2)))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun errorBody(code: Int, message: String, data: JsonElement? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }.toString()

    private fun daemonThreads(prefix: String): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { task -> Thread(task, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true } }
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
            // Already closed.
        }
    }

    private companion object {
        const val CALLER_PREFIX = "tailnet:"
        const val BACKLOG = 16
        const val STOP_WAIT_MS = 2_000L
        const val HANDLER_KEEP_ALIVE_S = 30L
        const val ACCEPT_RETRY_MS = 50L
        const val MAX_ECHO = 80
        const val DRAIN_MS = 1_000L
        const val DRAIN_MAX_BYTES = 64 * 1024
        const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val INTERNAL_ERROR = -32603
        const val HEADER_MISMATCH = -32020
        const val UNSUPPORTED_PROTOCOL_VERSION = -32022

        /** token68 of RFC 9110: what a Bearer token may contain. */
        val TOKEN68 = Regex("[A-Za-z0-9\\-._~+/]+=*")
        val NO_STORE_HEADERS = listOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        val JSON_HEADERS = NO_STORE_HEADERS + ("Content-Type" to "application/json")
    }
}
