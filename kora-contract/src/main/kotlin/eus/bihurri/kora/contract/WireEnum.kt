package eus.bihurri.kora.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.concurrent.ConcurrentHashMap

/**
 * An enum whose constants travel on the wire as stable strings: lower snake_case, unless the values come from an
 * established vocabulary that consumers pass on unchanged (MIME types such as `image/png`).
 */
public interface WireEnum {
    /** The string written to the wire. Part of the contract: never change it for a released constant. */
    public val wireName: String
}

/**
 * Serializer for wire enums that never fails on a constant it does not know.
 *
 * A newer minor version may add enum constants. The plain generated serializer throws on them, which would
 * break every older reader. This serializer maps any unrecognised string to [fallback] instead, so an older
 * reader keeps working and can treat the value as "something newer than I understand". The behaviour is the
 * same for every [KoraCodec] because it does not depend on format-specific options such as
 * `coerceInputValues` (which `Cbor` does not have).
 *
 * Every wire enum therefore needs a fallback constant (conventionally `UNKNOWN`). Use it as:
 * ```
 * @Serializable(with = Status.Serializer::class)
 * public enum class Status(override val wireName: String) : WireEnum {
 *     IDLE("idle"), UNKNOWN("unknown");
 *     internal object Serializer : TolerantEnumSerializer<Status>("pkg.Status", entries, UNKNOWN)
 * }
 * ```
 * The descriptor is a plain string, because `SerialDescriptor` cannot be implemented outside kotlinx.serialization
 * to declare an enum kind. Schema generators that need the allowed values call [WireEnums.allowedValues] (and
 * [WireEnums.fallbackValue] for what a reader may produce) with the descriptor, which every instance of this
 * serializer registers when it is created.
 */
public open class TolerantEnumSerializer<E>(
    serialName: String,
    private val entries: List<E>,
    private val fallback: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    private val byWireName: Map<String, E> = entries.associateBy { it.wireName }

    init {
        require(byWireName.size == entries.size) { "Duplicate wire name in enum '$serialName'" }
        require(fallback in entries) { "Fallback $fallback is not a constant of enum '$serialName'" }
        WireEnums.register(serialName, entries.filter { it != fallback }.map { it.wireName }, fallback.wireName)
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): E = byWireName[decoder.decodeString()] ?: fallback
}

/**
 * Registry of the values wire enums accept, for tools that describe the contract (JSON Schema, documentation).
 *
 * A [TolerantEnumSerializer] registers itself when it is created, and the serializer exists before any descriptor
 * it produced is reachable (a parent's `getElementDescriptor` asks the child serializer for it), so a descriptor
 * found while walking a type can always be looked up here. No reflection is involved.
 */
public object WireEnums {
    private data class Entry(val allowed: List<String>, val fallback: String)

    private val registry = ConcurrentHashMap<String, Entry>()

    internal fun register(serialName: String, values: List<String>, fallback: String) {
        val entry = Entry(values, fallback)
        val previous = registry.putIfAbsent(serialName, entry)
        require(previous == null || previous == entry) { "Wire enum '$serialName' is registered with different values" }
    }

    /**
     * The wire names a writer may produce for the enum described by [descriptor], in declaration order and without
     * the `unknown` fallback (which only a reader produces, see [fallbackValue]). Null when [descriptor] does not
     * describe a wire enum.
     */
    public fun allowedValues(descriptor: SerialDescriptor): List<String>? = entry(descriptor)?.allowed

    /**
     * The wire name of the fallback constant of the enum described by [descriptor] (conventionally `unknown`): what a
     * reader decodes a constant it does not know into, and writes again when it passes the value on, for example an
     * MCP server relaying a newer peer's answer. A writer never produces it on its own. Null when [descriptor] does
     * not describe a wire enum.
     */
    public fun fallbackValue(descriptor: SerialDescriptor): String? = entry(descriptor)?.fallback

    private fun entry(descriptor: SerialDescriptor): Entry? = registry[descriptor.serialName.removeSuffix("?")]
}
