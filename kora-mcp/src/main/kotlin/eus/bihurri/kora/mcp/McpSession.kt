package eus.bihurri.kora.mcp

import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.JsonGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val INTERNAL_ERROR = -32603
private const val UNSUPPORTED_PROTOCOL_VERSION = -32022

private const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
private const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
private const val META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId"
private const val LIST_CHANGED = "notifications/tools/list_changed"
private const val MAX_ECHO = 80

/**
 * One MCP connection (a stdio process, or an HTTP session) served by a [KoraNode]: JSON-RPC in, JSON-RPC out,
 * with no transport inside. A transport reads a message, calls [handle] (or [serve] when it can stream) and writes
 * the answer; it also forwards [notifications] while the legacy handshake is complete.
 *
 * ## What it serves
 * `initialize` and `notifications/initialized` (legacy), `server/discover` (modern), `ping`, `tools/list` with
 * cursor pagination, `tools/call`, and `subscriptions/listen` for `toolsListChanged` (modern). Resources, prompts,
 * logging, completions, sampling and Kora events are not offered: events are subscriptions, not tools, and are out
 * of scope for this module. JSON-RPC batches are rejected (`-32600`): they were removed from MCP in `2025-06-18`.
 * Notifications never get an answer, even when they are malformed or unknown.
 *
 * ## Tools
 * The tools are [McpTools] of the built-in capabilities the node can reach right now (provided by the node itself or
 * by a compatible peer), so the list changes when a peer joins or leaves. `tools/list` does not depend on the
 * caller: the catalogue is the same for every agent, and the caller's grants are enforced when a tool is called.
 * A tool that is known but not reachable answers `provider_unavailable` as a tool error.
 *
 * A successful result carries the output as `structuredContent` and, in `content`, one `text` block with the same
 * JSON, except for artwork, which is an `image` block plus a metadata text ([ToolContent]).
 *
 * ## Security
 * Every call is made as [caller], fixed when the session is created by the transport from facts it verified
 * (a pairing token, a client certificate, the uid behind an adb call): the JSON-RPC payload, including
 * `_meta.clientInfo`, never influences identity. [caller] must be an external kind ([serves]: [CallerKind.REMOTE_AGENT],
 * [CallerKind.SHELL] or [CallerKind.EXTERNAL_APP]), so the node's policy requires the master switch and an explicit
 * grant for each scope, and every call is audited. [CallerKind.SELF] and [CallerKind.PEER] are refused: they hold
 * every scope and have the typed API, so an agent must never ride on their identity. A refusal is a tool result
 * with `isError: true`.
 *
 * ## Errors
 * Protocol failures are JSON-RPC errors: `-32700` parse, `-32600` invalid request, `-32601` unknown method,
 * `-32602` invalid params (including an unknown tool and a request that lacks the required `_meta`), `-32603`
 * internal, `-32022` for a protocol version this server does not speak. Failures of the tool itself are tool results
 * with `isError: true` and a text such as `unauthorized: Scope 'media.library.read' is not granted`.
 *
 * ## Which era a request belongs to
 * A request whose `_meta` carries `io.modelcontextprotocol/protocolVersion` is served statelessly, as the modern
 * revision defines. Any other request needs a completed `initialize` on this session; before that it is rejected
 * with `-32602`, which is also what the modern revision prescribes for a request that lacks its `_meta`. `ping` and
 * `initialize` are always accepted. `initialize` answers the requested version when it is a legacy revision and
 * `2025-11-25`, the newest handshake-based one, otherwise.
 *
 * @param node the node whose capabilities become tools.
 * @param caller who is on the other end. Must be an external kind, see [serves].
 * @param serverInfo identity reported to the client.
 * @param gate how tools are called. The default forwards to peers under the node's policy ([JsonGate] with
 *   `forwardToPeers`); pass your own to set timeouts or size limits, or to match a non-default node codec, and keep
 *   `forwardToPeers = true` if tools of peer capabilities should work.
 * @param pageSize tools per `tools/list` page.
 * @param tools the tools to serve, one per operation of every capability version in its registry (by default the
 *   built-in ones). Pass the same registry to [gate] if the gate must call them.
 */
public class McpSession(
    private val node: KoraNode,
    private val caller: Caller,
    private val serverInfo: McpServerInfo,
    private val gate: JsonGate = JsonGate(node, forwardToPeers = true),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val tools: McpToolSet = McpTools.builtIn,
) {
    init {
        require(serves(caller.kind)) { "An MCP session acts for an external caller, not $caller" }
        require(pageSize > 0) { "pageSize must be positive: $pageSize" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var negotiated = false

    @Volatile
    private var initialized = false

    /**
     * `notifications/tools/list_changed` messages (JSON text) for the legacy handshake: emitted once the client has
     * sent `notifications/initialized`, each time the set of reachable tools changes. The transport collects it for
     * the life of the session and writes every item to the client. Modern clients get theirs on the stream opened
     * by `subscriptions/listen` instead ([serve]); this flow stays silent for them.
     */
    public val notifications: Flow<String> = toolListChanges()
        .filter { initialized }
        .map { encode(notification(LIST_CHANGED)) }

    /**
     * Answers one incoming JSON-RPC message with at most one message: null for notifications and for responses.
     * Never throws for a bad message; cancellation propagates. `subscriptions/listen` needs a stream, so here it is
     * answered `-32601`; use [serve] on a transport that can keep a response open.
     */
    public suspend fun handle(message: String): String? =
        when (val outcome = process(message)) {
            is Outcome.Reply -> outcome.message?.let(::encode)
            is Outcome.Listen -> encode(error(outcome.id, METHOD_NOT_FOUND, "subscriptions/listen needs a streaming transport"))
        }

    /**
     * Like [handle] for a transport that can stream: the flow emits the answer to [message] (nothing for a
     * notification) and completes, except for `subscriptions/listen`, where it emits the acknowledgement and then
     * one `notifications/tools/list_changed` per change until the collector is cancelled (the transport closes the
     * stream or the client sends `notifications/cancelled`).
     */
    public fun serve(message: String): Flow<String> = flow {
        when (val outcome = process(message)) {
            is Outcome.Reply -> outcome.message?.let { emit(encode(it)) }
            is Outcome.Listen -> emitAll(outcome.stream.map(::encode))
        }
    }

    // ---- Dispatch ----------------------------------------------------------------------------------------------

    private sealed interface Outcome {
        class Reply(val message: JsonObject?) : Outcome

        class Listen(val id: JsonElement, val stream: Flow<JsonObject>) : Outcome
    }

    private class RpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

    private suspend fun process(text: String): Outcome {
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            return reply(error(JsonNull, PARSE_ERROR, "Parse error"))
        }
        if (root is JsonArray) return reply(error(JsonNull, INVALID_REQUEST, "Batch requests are not supported"))
        if (root !is JsonObject) return reply(error(JsonNull, INVALID_REQUEST, "A JSON-RPC message must be an object"))
        val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // A response to something this server never asked is ignored; anything else is not a message.
            if ("result" in root || "error" in root) return Outcome.Reply(null)
            return reply(error(idOf(root) ?: JsonNull, INVALID_REQUEST, "Missing method"))
        }
        if ((root["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
            return if ("id" in root) reply(error(idOf(root) ?: JsonNull, INVALID_REQUEST, "jsonrpc must be \"2.0\""))
            else Outcome.Reply(null)
        }
        if ("id" !in root) {
            notified(method)
            return Outcome.Reply(null)
        }
        val id = idOf(root) ?: return reply(error(JsonNull, INVALID_REQUEST, "id must be a string or an integer"))
        return try {
            val params = when (val raw = root["params"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw RpcException(INVALID_PARAMS, "params must be an object")
            }
            request(id, method, params)
        } catch (e: RpcException) {
            reply(error(id, e.code, e.message.orEmpty(), e.data))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reply(error(id, INTERNAL_ERROR, "Internal error"))
        }
    }

    private fun reply(message: JsonObject) = Outcome.Reply(message)

    private fun idOf(root: JsonObject): JsonPrimitive? =
        (root["id"] as? JsonPrimitive)?.takeIf { it.isString || it.longOrNull != null }

    private fun notified(method: String) {
        if (method == "notifications/initialized" && negotiated) initialized = true
    }

    private suspend fun request(id: JsonPrimitive, method: String, params: JsonObject): Outcome = when (method) {
        "initialize" -> reply(result(id, initialize(params)))
        "ping" -> reply(result(id, JsonObject(emptyMap())))
        "server/discover" -> reply(result(id, discover(params)))
        "tools/list" -> {
            val era = eraOf(params)
            reply(result(id, complete(era, listTools(params, era))))
        }
        "tools/call" -> {
            val era = eraOf(params)
            reply(result(id, complete(era, callTool(params))))
        }
        "subscriptions/listen" -> listen(id, params)
        else -> throw RpcException(METHOD_NOT_FOUND, "Method not found: ${method.take(MAX_ECHO)}")
    }

    // ---- Eras --------------------------------------------------------------------------------------------------

    private enum class Era { LEGACY, MODERN }

    /** The era of a request, validating the per-request metadata of a modern one. */
    private fun eraOf(params: JsonObject): Era {
        val meta = when (val raw = params["_meta"]) {
            null, JsonNull -> null
            is JsonObject -> raw
            else -> throw RpcException(INVALID_PARAMS, "_meta must be an object")
        }
        val version = meta?.get(META_VERSION)
        if (version == null) {
            if (negotiated) return Era.LEGACY
            throw RpcException(
                INVALID_PARAMS,
                "Missing _meta '$META_VERSION'; stateless clients send it on every request, others must call initialize first",
            )
        }
        val requested = (version as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RpcException(INVALID_PARAMS, "'$META_VERSION' must be a string")
        if (requested !in McpProtocol.MODERN_VERSIONS) {
            throw RpcException(
                UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol version",
                buildJsonObject {
                    putJsonArray("supported") { McpProtocol.SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } }
                    put("requested", requested.take(MAX_ECHO))
                },
            )
        }
        if (meta[META_CAPABILITIES] !is JsonObject) {
            throw RpcException(INVALID_PARAMS, "Missing _meta '$META_CAPABILITIES'")
        }
        return Era.MODERN
    }

    /** Adds what a modern result must carry: `resultType` and the server identity in `_meta`. */
    private fun complete(era: Era, body: JsonObject): JsonObject = when (era) {
        Era.LEGACY -> body
        Era.MODERN -> buildJsonObject {
            put("resultType", "complete")
            body.forEach { (key, value) -> put(key, value) }
            putJsonObject("_meta") { put(META_SERVER_INFO, serverInfoJson()) }
        }
    }

    private fun serverInfoJson() = buildJsonObject {
        put("name", serverInfo.name)
        serverInfo.title?.let { put("title", it) }
        put("version", serverInfo.version)
    }

    private fun capabilities() = buildJsonObject {
        putJsonObject("tools") { put("listChanged", true) }
    }

    // ---- Lifecycle ---------------------------------------------------------------------------------------------

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RpcException(INVALID_PARAMS, "protocolVersion is required")
        val agreed = if (requested in McpProtocol.LEGACY_VERSIONS) requested else McpProtocol.LEGACY_VERSIONS.first()
        initialized = false
        negotiated = true
        return buildJsonObject {
            put("protocolVersion", agreed)
            put("capabilities", capabilities())
            put("serverInfo", serverInfoJson())
            serverInfo.instructions?.let { put("instructions", it) }
        }
    }

    private fun discover(params: JsonObject): JsonObject {
        val era = (params["_meta"] as? JsonObject)?.get(META_VERSION)
            ?.let { eraOf(params) }
            ?: throw RpcException(INVALID_PARAMS, "server/discover needs _meta '$META_VERSION' and '$META_CAPABILITIES'")
        return complete(
            era,
            buildJsonObject {
                putJsonArray("supportedVersions") { McpProtocol.SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } }
                put("capabilities", capabilities())
                serverInfo.instructions?.let { put("instructions", it) }
                put("ttlMs", 0)
                put("cacheScope", "private")
            },
        )
    }

    // ---- Tools -------------------------------------------------------------------------------------------------

    private fun reachableTools(): List<McpTool> = tools.all.filter { node.isAvailable(it.capability) }

    private fun listTools(params: JsonObject, era: Era): JsonObject {
        val offset = when (val cursor = params["cursor"]) {
            null, JsonNull -> 0
            is JsonPrimitive -> if (cursor.isString) decodeCursor(cursor.content) else throw invalidCursor()
            else -> throw invalidCursor()
        }
        val tools = reachableTools()
        if (offset > tools.size) throw invalidCursor()
        val page = tools.drop(offset).take(pageSize)
        val next = offset + page.size
        return buildJsonObject {
            put("tools", JsonArray(page.map { it.toJson() }))
            if (next < tools.size) put("nextCursor", encodeCursor(next))
            if (era == Era.MODERN) {
                // Reachability changes with peers coming and going, so the list is never fresh for long; the
                // list_changed notification invalidates it anyway. Private: it reveals which apps are installed.
                put("ttlMs", 0)
                put("cacheScope", "private")
            }
        }
    }

    private fun encodeCursor(offset: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("o$offset".encodeToByteArray())

    private fun decodeCursor(cursor: String): Int = try {
        val text = Base64.getUrlDecoder().decode(cursor).decodeToString()
        require(text.startsWith("o"))
        text.substring(1).toInt().also { require(it >= 0) }
    } catch (_: IllegalArgumentException) {
        throw invalidCursor()
    }

    private fun invalidCursor() = RpcException(INVALID_PARAMS, "Invalid cursor")

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RpcException(INVALID_PARAMS, "'name' is required and must be a string")
        val arguments = when (val raw = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw RpcException(INVALID_PARAMS, "'arguments' must be an object")
        }
        val tool = tools.find(name) ?: throw RpcException(INVALID_PARAMS, "Unknown tool: ${name.take(MAX_ECHO)}")
        val argument = buildJsonObject {
            put("capability", tool.capability.id.value)
            put("version", tool.capability.version.toString())
            put("operation", tool.operation.name)
            put("input", arguments)
        }
        val answer = json.parseToJsonElement(gate.call(caller, encode(argument))) as JsonObject
        return if (answer["ok"] == JsonPrimitive(true)) {
            val output = answer["output"] ?: JsonNull
            val structured = if (tool.wrapsOutput) buildJsonObject { put("result", output) } else output
            toolResult(tool.content.render(structured, json), structured, isError = false)
        } else {
            val error = answer["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: "internal"
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val text = if (message.isEmpty()) code else "$code: $message"
            toolResult(JsonArray(listOf(ToolContent.textBlock(text))), structured = null, isError = true)
        }
    }

    /** A tool result; [content] comes from the tool's [ToolContent] (a success) or is one error text block. */
    private fun toolResult(content: JsonArray, structured: JsonElement?, isError: Boolean) = buildJsonObject {
        put("content", content)
        structured?.let { put("structuredContent", it) }
        put("isError", isError)
    }

    // ---- Change notifications ----------------------------------------------------------------------------------

    /** Emits each time the set of reachable capabilities changes, not for the state at collection time. */
    private fun toolListChanges(): Flow<Unit> =
        combine(tools.registry.all.map { node.availability(it) }) { available -> available.toList() }
            .distinctUntilChanged()
            .drop(1)
            .map { }

    private fun listen(id: JsonPrimitive, params: JsonObject): Outcome {
        if (eraOf(params) != Era.MODERN) throw RpcException(INVALID_PARAMS, "subscriptions/listen is a stateless-era method")
        val requested = params["notifications"] as? JsonObject
            ?: throw RpcException(INVALID_PARAMS, "'notifications' is required and must be an object")
        val wantsTools = (requested["toolsListChanged"] as? JsonPrimitive)?.booleanOrNull == true
        val stream = flow {
            emit(
                notification("notifications/subscriptions/acknowledged") {
                    put("notifications", buildJsonObject { if (wantsTools) put("toolsListChanged", true) })
                    subscriptionMeta(id)
                },
            )
            if (wantsTools) {
                emitAll(toolListChanges().map { notification(LIST_CHANGED) { subscriptionMeta(id) } })
            } else {
                awaitCancellation()
            }
        }
        return Outcome.Listen(id, stream)
    }

    private fun JsonObjectBuilder.subscriptionMeta(id: JsonPrimitive) {
        putJsonObject("_meta") { put(META_SUBSCRIPTION, id) }
    }

    // ---- JSON-RPC encoding -------------------------------------------------------------------------------------

    private fun encode(message: JsonElement): String = json.encodeToString(JsonElement.serializer(), message)

    private fun result(id: JsonElement, body: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", body)
    }

    private fun error(id: JsonElement, code: Int, message: String, data: JsonElement? = null) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }

    private fun notification(
        method: String,
        params: (JsonObjectBuilder.() -> Unit)? = null,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        params?.let { put("params", buildJsonObject(it)) }
    }

    public companion object {
        /** Tools per `tools/list` page unless the transport asks for another size. */
        public const val DEFAULT_PAGE_SIZE: Int = 50

        /**
         * Whether a session may act for a caller of [kind]: the kinds the node's policy gates with the master switch
         * and per-caller grants ([CallerKind.REMOTE_AGENT], [CallerKind.SHELL], [CallerKind.EXTERNAL_APP]). A
         * transport checks it before creating a session for a caller it has identified.
         */
        public fun serves(kind: CallerKind): Boolean = kind in SERVED_KINDS

        private val SERVED_KINDS = setOf(CallerKind.REMOTE_AGENT, CallerKind.SHELL, CallerKind.EXTERNAL_APP)
    }
}
