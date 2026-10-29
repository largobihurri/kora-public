package eus.bihurri.kora.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlin.io.encoding.Base64

/**
 * Serializer of binary contract fields: a CBOR **byte string** between peers and a standard Base64 **string**
 * (RFC 4648 alphabet, with padding) in JSON, the form MCP clients expect.
 *
 * A binary property carries both annotations, because the CBOR byte string is chosen per property:
 * ```
 * @ByteString @Serializable(with = BytesSerializer::class) public val bytes: ByteArray
 * ```
 * Without `@ByteString` CBOR would write an array of integers. JSON input that is not valid padded Base64 is
 * rejected with an `IllegalArgumentException` (the `invalid_payload` of a gate).
 *
 * The descriptor is a string named [SERIAL_NAME], so schema generators can tell a binary field from text (the MCP
 * JSON Schema adds `"contentEncoding":"base64"`).
 */
public object BytesSerializer : KSerializer<ByteArray> {
    /** Serial name of [descriptor]. */
    public const val SERIAL_NAME: String = "eus.bihurri.kora.contract.Bytes"

    private val raw = ByteArraySerializer()

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        if (encoder is JsonEncoder) {
            encoder.encodeString(Base64.encode(value))
        } else {
            encoder.encodeSerializableValue(raw, value)
        }
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        if (decoder is JsonDecoder) {
            Base64.decode(decoder.decodeString())
        } else {
            decoder.decodeSerializableValue(raw)
        }
}
