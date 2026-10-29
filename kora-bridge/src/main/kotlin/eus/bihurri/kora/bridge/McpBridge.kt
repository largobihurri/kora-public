package eus.bihurri.kora.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader

private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val INTERNAL_ERROR = -32603
private val KEYWORDS = setOf("true", "false", "null")

/** JSON-RPC server error range, used for failures of the bridge or the device rather than of the request. */
private const val DEVICE_ERROR = -32000

/**
 * The MCP stdio transport: reads newline-delimited JSON-RPC messages (UTF-8, one per line) from [input], delivers
 * each to the phone through [channel], and writes the answer, if any, as one line to [output]. Everything else goes
 * to [log] (stderr in the real tool), never to [output], which belongs to the protocol.
 *
 * Messages are processed one at a time, in order: a slow tool call delays the messages behind it. The phone keeps
 * the session state, so the loop itself is stateless except for one thing: it remembers the client's `initialize`
 * and `notifications/initialized`. The app's process can be killed between two calls and its MCP session with it;
 * when the next request is answered "initialize first", the bridge replays the remembered handshake and retries
 * that request once, so the client never notices.
 *
 * Failures never end the loop. For a request (it has an `id`) they become a JSON-RPC error with the same id
 * (`-32000` when the device could not be reached or the gate refused, with the gate's code in `data.code`); for a
 * notification there is nobody to tell, so they are only logged. A line that is not JSON is `-32700` and one that is JSON but not an
 * object (a batch, a number) is `-32600`, both with a null id and answered by the bridge without bothering the phone.
 * There is no server-initiated traffic on this transport:
 * list-changed notifications and `subscriptions/listen` streams are not delivered.
 */
internal class McpBridge(
    private val channel: DeviceChannel,
    private val input: BufferedReader,
    private val output: Appendable,
    private val log: (String) -> Unit,
) {
    private val json = Json

    private var initializeMessage: String? = null
    private var initializedMessage: String? = null

    /** Serves [input] until it ends. */
    fun run() {
        var first = true
        while (true) {
            val raw = input.readLine() ?: return
            val line = (if (first) raw.removePrefix("﻿") else raw).trim()
            first = false
            if (line.isNotEmpty()) serve(line)
        }
    }

    private fun serve(line: String) {
        val root = try {
            json.parseToJsonElement(line)
        } catch (_: IllegalArgumentException) {
            write(error(JsonNull, PARSE_ERROR, "Parse error"))
            return
        }
        if (root !is JsonObject) {
            // The lenient parser accepts a bare word as a literal; only real JSON values are a valid but useless request.
            val validJson = root is JsonArray || (root is JsonPrimitive && (root.isString || root.content in KEYWORDS || root.content.toDoubleOrNull() != null))
            write(if (validJson) error(JsonNull, INVALID_REQUEST, "Invalid Request: expected a JSON object") else error(JsonNull, PARSE_ERROR, "Parse error"))
            return
        }
        val method = root.get("method")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        val id = root.get("id")?.takeIf { it is JsonPrimitive && it !is JsonNull }
        val isRequest = method != null && id != null
        when (method) {
            "initialize" -> if (isRequest) initializeMessage = line
            "notifications/initialized" -> if (!isRequest) initializedMessage = line
        }

        val answer = try {
            exchange(line, method)
        } catch (e: DeviceException) {
            log("${method ?: "message"} failed: ${e.message}")
            if (isRequest) write(error(id, DEVICE_ERROR, "Kora bridge: ${e.message}", e.code))
            return
        }
        when {
            answer != null -> write(answer)
            isRequest -> write(error(id, INTERNAL_ERROR, "Kora bridge: the app gave no answer to ${method}"))
        }
    }

    /** Sends [line]; replays the handshake once if the app lost the session since the last call. */
    private fun exchange(line: String, method: String?): String? {
        val answer = channel.send(line)
        if (answer == null || method == "initialize" || !lostSession(answer)) return answer
        val initialize = initializeMessage ?: return answer
        log("The app lost the MCP session; replaying the handshake")
        channel.send(initialize)
        initializedMessage?.let { channel.send(it) }
        return channel.send(line)
    }

    /** True when [answer] is the app saying that this session never completed `initialize`. */
    private fun lostSession(answer: String): Boolean {
        if (!answer.contains("initialize first")) return false
        val code = try {
            json.parseToJsonElement(answer).jsonObject["error"]?.jsonObject?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
        } catch (_: IllegalArgumentException) {
            null
        }
        return code == "-32602"
    }

    private fun write(message: String) {
        // A JSON-RPC message on stdio must not contain a raw newline; the phone sends compact JSON, so this only
        // guards against an unexpected formatter.
        val line = if ('\n' in message || '\r' in message) json.parseToJsonElement(message).toString() else message
        output.append(line).append('\n')
        (output as? java.io.Flushable)?.flush()
    }

    private fun write(message: JsonElement) = write(message.toString())

    private fun error(id: JsonElement, code: Int, message: String, deviceCode: String? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                deviceCode?.let { put("data", buildJsonObject { put("code", it) }) }
            },
        )
    }
}
