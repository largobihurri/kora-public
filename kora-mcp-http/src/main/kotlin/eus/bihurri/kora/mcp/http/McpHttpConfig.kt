package eus.bihurri.kora.mcp.http

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunables of an [McpHttpServer]. The defaults suit a phone serving one or two agents.
 *
 * @param path the one URL path of the MCP endpoint; any other path is `404`. Query strings are ignored.
 * @param maxBodyBytes largest accepted request body; a bigger `Content-Length` is `413` before the body is read.
 * @param allowedOrigins values of the `Origin` header that are accepted, compared case-insensitively
 *   (`https://example.com`). Agents (Claude Code, SDKs) do not send `Origin`; browsers always do, so the empty
 *   default refuses every browser page, which is what stops DNS rebinding. A request without `Origin` is fine.
 * @param allowedHosts host names accepted in the `Host` header besides the address the server is bound to (which is
 *   always accepted, spelled as an IP, with or without the port). Compared case-insensitively, without a port; a port
 *   in the request must be the one the server listens on. To reach the phone by its MagicDNS name, add it:
 *   `setOf("pixel.tail1234.ts.net")`. Anything else is `421`. A second defence against DNS rebinding, next to
 *   [allowedOrigins].
 * @param maxConnections requests served at the same time; a connection above it is answered `503`.
 * @param maxConnectionsPerAddress connections one remote address may hold at the same time; above it `503`, before
 *   anything is read. Stops one peer from taking every worker.
 * @param headTimeout time allowed to receive the request line and headers, before the caller is known; the
 *   connection is dropped after it. Short on purpose: nobody is authenticated yet.
 * @param readTimeout time allowed to receive the body, counted from the moment the caller is authenticated; the
 *   connection is dropped after it.
 * @param requestTimeout time allowed for the node to answer once the request is in; `504` after it. It holds even
 *   when a handler blocks without checking for cancellation: the connection is answered and freed, and the handler
 *   thread is interrupted (it is released when the handler returns; see [McpHttpServer]).
 * @param deniedThrottle at most one [McpHttpListener.onDenied] call per remote address and reason per this time;
 *   the ones held back are counted in [DeniedAttempt.suppressed]. Zero delivers every one.
 * @param allowNonTailnetAddress lets [McpHttpServer.start] bind an address that is neither loopback nor in Tailscale's
 *   ranges (100.64.0.0/10, fd7a:115c:a1e0::/48). Off by default: it turns a mistake (the LAN or the Wi-Fi address)
 *   into an error instead of an open listener. Note that 100.64.0.0/10 is also what mobile carriers use for CGNAT,
 *   so passing this check does not prove the address belongs to the Tailscale interface.
 * @param writeTimeout time allowed to deliver the answer; the connection is dropped after it.
 * @param log receives one line per request (method, path, status, pairing id) and lifecycle events. Never gets a
 *   token, a header or a body. The default writes to `java.util.logging`, which Android shows in logcat.
 */
public class McpHttpConfig(
    public val path: String = DEFAULT_PATH,
    public val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    public val allowedOrigins: Set<String> = emptySet(),
    public val allowedHosts: Set<String> = emptySet(),
    public val maxConnections: Int = 8,
    public val maxConnectionsPerAddress: Int = 2,
    public val headTimeout: Duration = 3.seconds,
    public val readTimeout: Duration = 10.seconds,
    public val requestTimeout: Duration = 30.seconds,
    public val writeTimeout: Duration = 10.seconds,
    public val deniedThrottle: Duration = 10.seconds,
    public val allowNonTailnetAddress: Boolean = false,
    public val log: (String) -> Unit = JulLog::info,
) {
    init {
        require(path.startsWith("/") && path.none { it == '?' || it == '#' || it.isWhitespace() }) {
            "path must start with / and hold no query, fragment or whitespace: $path"
        }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive: $maxBodyBytes" }
        require(maxConnections > 0) { "maxConnections must be positive: $maxConnections" }
        require(maxConnectionsPerAddress > 0) { "maxConnectionsPerAddress must be positive: $maxConnectionsPerAddress" }
        require(headTimeout.isPositive() && readTimeout.isPositive() && requestTimeout.isPositive() && writeTimeout.isPositive()) {
            "Timeouts must be positive"
        }
        require(!deniedThrottle.isNegative()) { "deniedThrottle must not be negative: $deniedThrottle" }
    }

    public companion object {
        /** The endpoint path the MCP clients are usually configured with. */
        public const val DEFAULT_PATH: String = "/mcp"

        /** 1 MiB. */
        public const val DEFAULT_MAX_BODY_BYTES: Int = 1 shl 20
    }

    private object JulLog {
        private val logger = Logger.getLogger("eus.bihurri.kora.mcp.http")

        fun info(line: String) = logger.log(Level.INFO, line)
    }
}

/** Why a request was turned away before it reached the node. */
public enum class DenialReason {
    /** No `Authorization: Bearer` header (or not a Bearer one). */
    MISSING_TOKEN,

    /** A token that matches no pairing: wrong, malformed, or revoked. */
    INVALID_TOKEN,

    /** An `Origin` that is not in [McpHttpConfig.allowedOrigins], typically a web page. */
    FOREIGN_ORIGIN,

    /** A `Host` that is neither the bound address nor in [McpHttpConfig.allowedHosts]. */
    FOREIGN_HOST,
}

/**
 * A request that was refused. Nothing the client sent is kept besides the facts below, so the app can show "someone
 * at 100.x tried without a valid token" without ever holding the secret they tried.
 */
public class DeniedAttempt(
    public val reason: DenialReason,
    /** Textual IP address of the peer (never a host name: no DNS lookup is made). */
    public val remoteAddress: String,
    public val atEpochMs: Long,
    /** Attempts of the same address and reason that [McpHttpConfig.deniedThrottle] held back since the last call. */
    public val suppressed: Int = 0,
)

/**
 * Hooks for the app that hosts the server. Both are called on the connection's worker thread, before the request is
 * answered: record and return. [onDenied] is throttled per address and reason ([McpHttpConfig.deniedThrottle]), so a
 * flood does not reach the app. Anything thrown from a hook is swallowed.
 */
public interface McpHttpListener {
    /**
     * A request carried a valid token of [pairing] and is about to be served. Reset the auto-off timer here. It does
     * not matter whether the request later succeeds.
     */
    public fun onRequest(pairing: Pairing) {}

    /** A request was refused for authentication, `Origin` or `Host` reasons. */
    public fun onDenied(attempt: DeniedAttempt) {}

    /** The listener that ignores everything. */
    public companion object None : McpHttpListener
}
