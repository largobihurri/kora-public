package eus.bihurri.kora.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/*
 * Wire envelope. Payloads are opaque bytes encoded with a [KoraCodec] and the serializers of the addressed
 * OperationSpec/EventSpec, so the envelope never changes when a capability evolves.
 * In CBOR a payload is a byte string; in JSON it is an array of numbers (JSON is for debugging only).
 */

/** Call of [operation] on [capability], addressed to a provider that must satisfy [version]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public class KoraRequest(
    public val id: String,
    public val capability: String,
    public val version: CapabilityVersion,
    public val operation: String,
    @ByteString public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KoraRequest &&
            id == other.id &&
            capability == other.capability &&
            version == other.version &&
            operation == other.operation &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + capability.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "KoraRequest(id=$id, $capability.$operation@$version, ${payload.size} bytes)"
}

/** Outcome of a [KoraRequest]; [id] echoes the request id. Serialize with `KoraResponse.serializer()`. */
@Serializable
public sealed interface KoraResponse {
    public val id: String

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("success")
    public class Success(
        override val id: String,
        @ByteString public val payload: ByteArray,
    ) : KoraResponse {
        override fun equals(other: Any?): Boolean =
            other is Success && id == other.id && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()

        override fun toString(): String = "Success(id=$id, ${payload.size} bytes)"
    }

    @Serializable
    @SerialName("failure")
    public data class Failure(override val id: String, public val error: KoraError) : KoraResponse
}

/** Opens a stream of [event]s of [capability]; events carry back the caller-chosen [subscriptionId]. */
@Serializable
public data class SubscribeRequest(
    public val subscriptionId: String,
    public val capability: String,
    public val version: CapabilityVersion,
    public val event: String,
)

/** One pushed event of the subscription [subscriptionId]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public class KoraEvent(
    public val subscriptionId: String,
    public val capability: String,
    public val event: String,
    @ByteString public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KoraEvent &&
            subscriptionId == other.subscriptionId &&
            capability == other.capability &&
            event == other.event &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = subscriptionId.hashCode()
        result = 31 * result + capability.hashCode()
        result = 31 * result + event.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "KoraEvent($subscriptionId, $capability.$event, ${payload.size} bytes)"
}
