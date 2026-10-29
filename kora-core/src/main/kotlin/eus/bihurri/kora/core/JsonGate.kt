package eus.bihurri.kora.core

import eus.bihurri.kora.contract.Capabilities
import eus.bihurri.kora.contract.CapabilityRegistry
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NodeDescriptor
import eus.bihurri.kora.contract.OperationSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calls a [KoraNode] with JSON, for people, scripts and agents (adb, MCP) that cannot build CBOR envelopes.
 *
 * The argument names a built-in capability and operation and carries the typed input as a nested JSON value,
 * never as number arrays:
 *
 * ```
 * {"capability":"media.library","version":"1.0","operation":"list_playlists","input":{"limit":10,"offset":0}}
 * ```
 *
 * `version` is optional and so is `input` (an empty object). With a `version`, the definition of that major is
 * used (a major the registry lacks is answered by the node, e.g. `incompatible_version`); without one, the call is made
 * at the newest built-in version of the capability, so a script that must keep its shape across a new major should
 * always send the `version` it was written for. Unknown
 * keys are ignored, like everywhere else in Kora. The answer is always one JSON object:
 *
 * ```
 * {"ok":true,"output":{...}}
 * {"ok":false,"error":{"code":"unauthorized","message":"..."}}
 * ```
 *
 * `output` is the typed output as JSON (`{}` for operations that return nothing). Error codes are the contract's
 * wire names; a malformed argument is `invalid_payload`.
 *
 * The call goes through [KoraNode.handle], so capability lookup, version check, the caller policy and the audit
 * trail apply exactly as for any other transport. The class translates only the representation: the JSON input
 * is decoded with the operation's input serializer, encoded with the node's [codec], and the answer travels the
 * opposite way. An operation or input that cannot be translated is still sent to the node, with an empty
 * payload, so that an unauthorized caller is refused and audited (and `onDenied` fires) before it learns
 * anything about the contract.
 *
 * Only the capabilities in [registry] (by default [Capabilities]) can be called this way.
 *
 * @param node the node to call.
 * @param codec the codec of the node's payloads; it must be the one [node] was built with.
 * @param timeout limit for one call; the answer is `timeout` when the handler takes longer. A call that times
 *   out is cancelled and, like any cancelled call, not audited.
 * @param maxRequestBytes largest accepted argument (UTF-8 bytes); above it the answer is `payload_too_large`.
 * @param maxResponseBytes largest answer (UTF-8 bytes); above it the answer is `payload_too_large`.
 * @param forwardToPeers when true, capabilities that only a peer offers are served too, through [KoraNode.dispatch]
 *   (the node's policy authorizes the caller, then the request is forwarded). Off by default: a surface that
 *   exposes only what this app provides, like the Android content provider, must not become a relay to its peers.
 * @param registry the definitions that can be called through JSON, looked up by id and major.
 */
public class JsonGate(
    private val node: KoraNode,
    private val codec: KoraCodec = CborCodec,
    private val timeout: Duration = 5.seconds,
    private val maxRequestBytes: Int = Int.MAX_VALUE,
    private val maxResponseBytes: Int = Int.MAX_VALUE,
    private val forwardToPeers: Boolean = false,
    private val registry: CapabilityRegistry = Capabilities.registry,
) {
    init {
        require(timeout.isPositive()) { "timeout must be positive: $timeout" }
        require(maxRequestBytes > 0 && maxResponseBytes > 0) { "Size limits must be positive" }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sequence = AtomicLong()

    /** The descriptor of the node as JSON (`nodeId`, `displayName`, `capabilities`). */
    public fun describe(): String = json.encodeToString(NodeDescriptor.serializer(), node.describe())

    /**
     * Serves [argument] (see the class documentation) for [caller] and returns the JSON answer. Never throws for a
     * failed call, whatever its cause; cancellation of the calling coroutine propagates.
     */
    public suspend fun call(caller: Caller, argument: String): String {
        val answer = try {
            if (argument.encodeToByteArray().size > maxRequestBytes) {
                failure(ErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds $maxRequestBytes bytes")
            } else {
                dispatch(caller, parse(argument))
            }
        } catch (e: KoraException) {
            failure(e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure(ErrorCode.INTERNAL, "Internal error")
        }
        val text = json.encodeToString(JsonObject.serializer(), answer)
        return if (text.encodeToByteArray().size > maxResponseBytes) {
            json.encodeToString(
                JsonObject.serializer(),
                failure(ErrorCode.PAYLOAD_TOO_LARGE, "Response exceeds $maxResponseBytes bytes"),
            )
        } else {
            text
        }
    }

    private class Invocation(
        val capability: String,
        val version: CapabilityVersion?,
        val operation: String,
        val input: JsonElement,
    )

    private fun parse(argument: String): Invocation {
        val root = try {
            json.parseToJsonElement(argument)
        } catch (_: IllegalArgumentException) {
            throw invalid("Request is not valid JSON")
        }
        val obj = root as? JsonObject ?: throw invalid("Request must be a JSON object")
        fun text(key: String): String {
            val value = obj[key] as? JsonPrimitive
            return value?.takeIf { it.isString }?.contentOrNull ?: throw invalid("'$key' is required and must be a string")
        }
        val version = obj["version"]?.let { raw ->
            val value = raw as? JsonPrimitive
            val parts = value?.takeIf { it.isString }?.content?.split('.')
            val major = parts?.getOrNull(0)?.toIntOrNull()
            val minor = parts?.getOrNull(1)?.toIntOrNull()
            if (parts == null || parts.size != 2 || major == null || minor == null || major < 0 || minor < 0) {
                throw invalid("'version' must be a string such as \"1.0\"")
            }
            CapabilityVersion(major, minor)
        }
        return Invocation(text("capability"), version, text("operation"), obj["input"] ?: JsonObject(emptyMap()))
    }

    private suspend fun dispatch(caller: Caller, invocation: Invocation): JsonObject {
        // An explicit version selects the definition of its major; without one, the newest built-in version.
        val explicit = invocation.version
        val capability = if (explicit != null) {
            registry.find(invocation.capability, explicit.major)
        } else {
            registry.find(invocation.capability)
        }
        val spec = capability?.findOperation(invocation.operation)
        val version = invocation.version ?: capability?.version ?: CapabilityVersion(1, 0)
        if (spec == null) {
            val message = if (capability == null) "Capability '${invocation.capability}' is not available through JSON"
            else "Unknown operation '${invocation.operation}'"
            return refused(caller, invocation, version, ErrorCode.UNKNOWN_OPERATION, message)
        }
        return invoke(caller, invocation, version, spec)
    }

    private suspend fun <I, O> invoke(
        caller: Caller,
        invocation: Invocation,
        version: CapabilityVersion,
        spec: OperationSpec<I, O>,
    ): JsonObject {
        val payload = try {
            spec.encodeInput(codec, json.decodeFromJsonElement(spec.inputSerializer, invocation.input))
        } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
            return refused(caller, invocation, version, ErrorCode.INVALID_PAYLOAD, "Input of '${spec.name}' is malformed: ${brief(e)}")
        }
        val response = handle(caller, request(invocation, version, payload))
        return when (response) {
            is KoraResponse.Failure -> failure(response.error)
            is KoraResponse.Success -> {
                val output = try {
                    json.encodeToJsonElement(spec.outputSerializer, spec.decodeOutput(codec, response.payload))
                } catch (_: IllegalArgumentException) {
                    throw KoraException(KoraError(ErrorCode.INTERNAL, "Malformed output of '${spec.name}'"))
                }
                buildJsonObject {
                    put("ok", true)
                    put("output", output)
                }
            }
        }
    }

    /**
     * Sends a request that cannot be translated to the node with an empty payload, so that the lookup, the policy
     * and the audit run first: a refused caller gets the node's answer (`unauthorized`, `unknown_capability`...),
     * and only an authorized one learns [code] and [message].
     */
    private suspend fun refused(
        caller: Caller,
        invocation: Invocation,
        version: CapabilityVersion,
        code: ErrorCode,
        message: String,
    ): JsonObject {
        val response = handle(caller, request(invocation, version, ByteArray(0)))
        return if (response is KoraResponse.Failure && response.error.code != ErrorCode.INVALID_PAYLOAD &&
            response.error.code != ErrorCode.UNKNOWN_OPERATION
        ) {
            failure(response.error)
        } else {
            failure(code, message)
        }
    }

    private fun request(invocation: Invocation, version: CapabilityVersion, payload: ByteArray) = KoraRequest(
        id = "gate-${sequence.incrementAndGet()}",
        capability = invocation.capability,
        version = version,
        operation = invocation.operation,
        payload = payload,
    )

    private suspend fun handle(caller: Caller, request: KoraRequest): KoraResponse =
        withTimeoutOrNull(timeout) { if (forwardToPeers) node.dispatch(caller, request) else node.handle(caller, request) }
            ?: KoraResponse.Failure(request.id, KoraError(ErrorCode.TIMEOUT, "No answer within $timeout"))

    private fun invalid(message: String) = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, message))

    private fun brief(e: Exception): String = (e.message ?: e::class.simpleName.orEmpty()).lineSequence().first().take(MAX_DETAIL)

    private fun failure(code: ErrorCode, message: String) = failure(KoraError(code, message))

    private fun failure(error: KoraError): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", json.encodeToJsonElement(KoraError.serializer(), error))
    }

    private companion object {
        const val MAX_DETAIL = 200
    }
}
