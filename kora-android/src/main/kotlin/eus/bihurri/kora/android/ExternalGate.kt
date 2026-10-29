package eus.bihurri.kora.android

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.KoraCodec
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.KoraNode
import eus.bihurri.kora.core.JsonGate
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * What `KoraGateProvider` does, free of `ContentProvider` and `Bundle` so it can be unit tested: serves a caller
 * that was already identified, in binary (CBOR envelopes) or in JSON ([JsonGate]).
 *
 * Both paths go through [KoraNode.handle], so the caller policy and the audit apply as on the Binder service.
 * Requests and answers above [maxBytes] are replaced by `payload_too_large` (see [PayloadLimits]) and a handler
 * that does not answer within [timeout] by `timeout`. Neither path throws for a failed call; only cancellation
 * propagates.
 */
internal class ExternalGate(
    private val node: KoraNode,
    private val timeout: Duration,
    private val maxBytes: Int = PayloadLimits.MAX_ENVELOPE_BYTES,
) {
    private val codec: KoraCodec = CborCodec
    private val jsonGate = JsonGate(node, codec, timeout, maxBytes, maxBytes)

    /** The descriptor of the node as JSON. */
    fun describe(): String = jsonGate.describe()

    /** Serves a JSON argument (see [JsonGate]) and returns the JSON answer. */
    suspend fun callJson(caller: Caller, argument: String): String = jsonGate.call(caller, argument)

    /** Serves one CBOR [KoraRequest] and returns the CBOR [KoraResponse]. */
    suspend fun callBinary(caller: Caller, request: ByteArray): ByteArray {
        if (request.size > maxBytes) {
            return GateFailures.binary("", ErrorCode.PAYLOAD_TOO_LARGE, "Request of ${request.size} bytes exceeds $maxBytes")
        }
        val decoded = try {
            codec.decode(KoraRequest.serializer(), request)
        } catch (_: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
            return GateFailures.binary("", ErrorCode.INVALID_PAYLOAD, "Malformed request")
        }
        val response = withTimeoutOrNull(timeout) { node.handle(caller, decoded) }
            ?: KoraResponse.Failure(decoded.id, KoraError(ErrorCode.TIMEOUT, "No answer within $timeout"))
        val bytes = codec.encode(KoraResponse.serializer(), response)
        return if (bytes.size > maxBytes) {
            GateFailures.binary(decoded.id, ErrorCode.PAYLOAD_TOO_LARGE, "Response of ${bytes.size} bytes exceeds $maxBytes")
        } else {
            bytes
        }
    }
}

/** Failures produced by the gate itself rather than by a node, in the two answer forms. Needs no installed node. */
internal object GateFailures {
    /** The binary form: a CBOR `KoraResponse.Failure` echoing [id] (empty when the request was not readable). */
    fun binary(id: String, code: ErrorCode, message: String, codec: KoraCodec = CborCodec): ByteArray =
        codec.encode(KoraResponse.serializer(), KoraResponse.Failure(id, KoraError(code, message)))

    /** The JSON form: `{"ok":false,"error":{"code":...,"message":...}}`. */
    fun json(code: ErrorCode, message: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("ok", false)
            put("error", Json.encodeToJsonElement(KoraError.serializer(), KoraError(code, message)))
        },
    )
}
