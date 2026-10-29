package eus.bihurri.kora.contract

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

/**
 * Turns contract values into bytes and back. Failures surface as `SerializationException` or
 * `IllegalArgumentException`; callers that cross a boundary map them to `invalid_payload`.
 *
 * Both implementations ignore unknown keys and encode default values, so a minor version can add optional
 * fields without breaking older readers (they skip them) or newer readers (they apply the default).
 *
 * Enum constants added by a newer minor version are tolerated too: wire enums use [TolerantEnumSerializer], so
 * an older reader decodes a constant it does not know as the enum's `UNKNOWN` entry instead of failing. The
 * guarantee covers values (new constants, new fields), not removals or renames, which need a new major version.
 */
public interface KoraCodec {
    public fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray

    public fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T
}

/** Compact binary format used between peers (Binder IPC). */
@OptIn(ExperimentalSerializationApi::class)
public object CborCodec : KoraCodec {
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(deserializer, bytes)
}

/** Textual format used for MCP and debugging; bytes are UTF-8 JSON. */
public object JsonCodec : KoraCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    override fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        json.decodeFromString(deserializer, bytes.decodeToString())
}
