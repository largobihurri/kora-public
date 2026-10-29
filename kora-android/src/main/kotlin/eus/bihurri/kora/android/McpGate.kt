package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.JsonGate
import eus.bihurri.kora.mcp.McpServerInfo
import eus.bihurri.kora.mcp.McpSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * What `KoraGate.METHOD_MCP` does, free of `ContentProvider` and `Bundle` so it can be unit tested: one JSON-RPC
 * message in, at most one JSON-RPC message out, served by an [McpSession] that lives as long as the runtime.
 *
 * The gate is stateless per call (every `content call` is a new Binder transaction), but the MCP handshake is not,
 * so the sessions are kept here, one per identified [Caller]. The caller always comes from the uid of the call
 * (adb is `shell`, an app is its package and certificate): nothing in the payload, `clientInfo` included, selects
 * a session or an identity, so the app's grants stay per caller exactly as for the other gate methods. Consequences
 * worth knowing:
 * - Everything arriving through adb shares the one `shell` session. Two MCP clients on the same device and app see
 *   each other's `initialize`; use one client per app.
 * - Sessions live in memory. When the app process dies a legacy client has to `initialize` again (the answer to
 *   its next request says so); a client that sends the per-request metadata of the stateless revision is unaffected.
 * - Only external kinds are served ([McpSession.serves]): `SELF` and `PEER` already have the typed API and hold
 *   every scope, so an agent must not borrow them.
 *
 * Tools of peer capabilities are served too (`forwardToPeers`): the hosting node authorizes the caller first.
 * Requests and answers above [maxBytes] are refused with `payload_too_large` (see [PayloadLimits]); the handler of
 * a tool that does not answer within [timeout] is reported by the tool as `timeout`. Nothing here streams:
 * `subscriptions/listen` is answered `-32601` and server-initiated notifications are never delivered.
 *
 * ## Encoding of the argument and the answer
 * A JSON-RPC message begins with `{` or `[`, so an argument that begins with [KoraGate.PREFIX_BASE64] cannot be
 * one: it is the message in UTF-8, URL-safe Base64 without padding, and the answer is encoded the same way. This
 * lets a transport pass any message through the quoting of `adb shell` without escaping. Any other argument is the
 * message itself and its answer is plain.
 */
internal class McpGate(
    private val node: KoraNode,
    private val serverInfo: McpServerInfo,
    timeout: Duration,
    private val maxBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
) {
    /** The outcome of one call. */
    sealed interface Answer {
        /** [message] is the JSON-RPC answer, null for a notification; [encoded] when it must travel as Base64. */
        class Reply(val message: String?, val encoded: Boolean) : Answer

        /** The gate refused the call before MCP saw it: the bundle carries only error keys. */
        class Refused(val code: ErrorCode, val message: String) : Answer
    }

    private val gate = JsonGate(node, CborCodec, timeout, maxBytes, maxBytes, forwardToPeers = true)
    /** One session per caller; [lock] serializes its messages, since `call` runs on a Binder thread pool. */
    private class Entry(val session: McpSession, val lock: Mutex = Mutex())

    private val sessions = ConcurrentHashMap<Caller, Entry>()

    suspend fun handle(caller: Caller, argument: String): Answer {
        if (!McpSession.serves(caller.kind)) {
            return Answer.Refused(ErrorCode.UNAUTHORIZED, "MCP is served to external callers only, not to ${caller.kind}")
        }
        val encoded = argument.startsWith(KoraGate.PREFIX_BASE64)
        val message = if (encoded) {
            if (argument.length > maxBytes * ENCODED_SLACK) return tooLarge("Request")
            decode(argument.substring(KoraGate.PREFIX_BASE64.length))
                ?: return Answer.Refused(ErrorCode.INVALID_PAYLOAD, "The argument is not UTF-8 text in URL-safe Base64")
        } else {
            argument
        }
        if (message.encodeToByteArray().size > maxBytes) return tooLarge("Request")

        val entry = sessions.computeIfAbsent(caller) { Entry(McpSession(node, caller, serverInfo, gate)) }
        val reply = entry.lock.withLock { entry.session.handle(message) } ?: return Answer.Reply(null, encoded)
        if (reply.encodeToByteArray().size > maxBytes) return tooLarge("Response")
        return Answer.Reply(if (encoded) encode(reply) else reply, encoded)
    }

    private fun tooLarge(what: String) = Answer.Refused(ErrorCode.PAYLOAD_TOO_LARGE, "$what exceeds $maxBytes bytes")

    private companion object {
        /**
         * Cheap pre-check before decoding: Base64 is 4/3 the size of what it encodes, so twice the byte limit is a
         * generous bound. The exact limit is enforced on the decoded bytes.
         */
        const val ENCODED_SLACK = 2

        fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())

        fun decode(text: String): String? = try {
            Base64.getUrlDecoder().decode(text).decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
