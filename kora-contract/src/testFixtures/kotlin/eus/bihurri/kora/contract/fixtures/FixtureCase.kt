package eus.bihurri.kora.contract.fixtures

import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.JsonCodec
import kotlinx.serialization.KSerializer

/** The two encodings every fixture is stored in. */
public enum class FixtureFormat(public val extension: String) {
    /** The bytes peers exchange, as one line of lowercase hex. */
    CBOR("cbor.hex"),

    /** The text MCP and debugging use. */
    JSON("json"),
    ;

    public companion object {
        /** The format of a file name, with the name it holds; null when the file is not a fixture. */
        public fun split(fileName: String): Pair<String, FixtureFormat>? =
            entries.firstNotNullOfOrNull { format ->
                fileName.removeSuffix(".${format.extension}").takeIf { it != fileName && it.isNotEmpty() }?.let { it to format }
            }
    }
}

/**
 * One deterministic sample value and where its encodings are stored: `wire/<group>/<version>/<name>.<ext>`.
 *
 * [group] is a capability id (`media.playback`) or a protocol (`envelope`, `wear`); [version] is that group's
 * version label (`1.0`); [name] identifies the payload inside the group, e.g. `seek_to.input`. A case describes the
 * **current** code: the serializer and value are what this build would produce.
 */
public class FixtureCase<T>(
    public val group: String,
    public val version: String,
    public val name: String,
    private val serializer: KSerializer<T>,
    private val value: T,
) {
    /** The canonical text stored for this case in [format]. */
    public fun encode(format: FixtureFormat): String = when (format) {
        FixtureFormat.CBOR -> CborCodec.encode(serializer, value).toHex()
        FixtureFormat.JSON -> JsonCodec.encode(serializer, value).decodeToString()
    }

    /**
     * Decodes [text] (stored in [format]) with the current serializer and encodes the result as JSON, a form that
     * compares equal exactly when the decoded values mean the same. Throws when the text no longer decodes.
     */
    public fun decodeToJson(format: FixtureFormat, text: String): String {
        val decoded = when (format) {
            FixtureFormat.CBOR -> CborCodec.decode(serializer, text.fromHex())
            FixtureFormat.JSON -> JsonCodec.decode(serializer, text.encodeToByteArray())
        }
        return JsonCodec.encode(serializer, decoded).decodeToString()
    }

    override fun toString(): String = "$group/$version/$name"
}

/** Lowercase hex, the text form of the stored CBOR fixtures. */
public fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Inverse of [toHex]; whitespace is ignored. */
public fun String.fromHex(): ByteArray {
    val hex = filterNot(Char::isWhitespace)
    require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Not lowercase hex" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
