package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.BytesSerializer
import eus.bihurri.kora.contract.IntegerRange
import eus.bihurri.kora.contract.WireEnums
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates JSON Schema (2020-12 compatible, no `$schema` key so the dialect defaults to it) from a
 * [SerialDescriptor], so the schemas of MCP tools always follow the contract types and never drift from them.
 *
 * Mapping:
 * - `String` and `Char` are `string`; `Boolean` is `boolean`; `Byte`, `Short`, `Int` and `Long` are `integer`;
 *   `Float` and `Double` are `number`.
 * - Binary fields ([BytesSerializer]) are `string` with `"contentEncoding":"base64"`, their JSON form.
 * - An integer property annotated with [IntegerRange] adds `minimum` and `maximum`, read from the descriptor's element
 *   annotations (no reflection). On any other kind of property the annotation is a contract bug and fails with
 *   `IllegalArgumentException`.
 * - Wire enums are `string` with `enum` listing [WireEnums.allowedValues]. The schema depends on the direction:
 *   an [input] schema never lists the `unknown` fallback ([WireEnums.fallbackValue]), since a caller must not send
 *   it; an [output] schema lists it last. A result is a read: the node may answer from a peer on a newer minor whose
 *   new constant this build decodes as `UNKNOWN`, and the JSON gate writes that back as `"unknown"`, so a result
 *   schema without it would reject a valid answer. Plain Kotlin enums list their constant names either way.
 * - Lists and sets are `array` with `items`; maps are `object` with `additionalProperties`.
 * - Classes and objects are `object` with `properties`. A class without properties is closed
 *   (`additionalProperties: false`), the form the MCP specification recommends for tools without parameters.
 *   Classes with properties stay open, because readers ignore unknown keys and newer minors add fields.
 * - A nullable type is `["<type>","null"]` for plain primitives and `anyOf` with `{"type":"null"}` otherwise.
 * - Polymorphic and contextual types, and a class that contains itself, become the open schema `{}` (or
 *   `{"type":"object"}` for the recursive reference). None of these exist in the built-in capabilities.
 *
 * In an [input] schema `required` lists the elements that have no default value. An [output] schema requires every
 * element: the JSON gate encodes defaults, so every element is always present in an answer.
 */
public object JsonSchemas {
    /** Schema of an MCP tool's `arguments` of the type described by [descriptor]: wire enums without the fallback. */
    public fun input(descriptor: SerialDescriptor): JsonObject =
        Generator(requireAll = false, admitFallback = false).run(descriptor)

    /**
     * Schema of an MCP tool's result of the type described by [descriptor]: every element required, and wire enums
     * admit their fallback (listed last), at any depth.
     */
    public fun output(descriptor: SerialDescriptor): JsonObject =
        Generator(requireAll = true, admitFallback = true).run(descriptor)

    /**
     * Schema of the type described by [descriptor] with wire enums as in [input]; [requireAll] only changes
     * `required`. Kept for existing callers: use [input] for arguments and [output] for results, which also admits
     * the fallback a reader produces.
     */
    public fun of(descriptor: SerialDescriptor, requireAll: Boolean = false): JsonObject =
        Generator(requireAll, admitFallback = false).run(descriptor)

    private val INTEGER_KINDS = setOf(PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG)

    private class Generator(private val requireAll: Boolean, private val admitFallback: Boolean) {
        fun run(descriptor: SerialDescriptor): JsonObject = schema(descriptor, emptySet())

        fun schema(descriptor: SerialDescriptor, visiting: Set<String>): JsonObject {
            val base = nonNull(descriptor, visiting)
            return if (descriptor.isNullable) nullable(base) else base
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun nonNull(descriptor: SerialDescriptor, visiting: Set<String>): JsonObject {
            if (descriptor.isInline) return schema(descriptor.getElementDescriptor(0), visiting)
            WireEnums.allowedValues(descriptor)?.let { allowed ->
                val fallback = WireEnums.fallbackValue(descriptor).takeIf { admitFallback }
                return enumOf(allowed + listOfNotNull(fallback))
            }
            if (descriptor.serialName.removeSuffix("?") == BytesSerializer.SERIAL_NAME) return base64()
            return when (val kind = descriptor.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> typed("string")
                PrimitiveKind.BOOLEAN -> typed("boolean")
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> typed("integer")
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number")
                SerialKind.ENUM -> enumOf(List(descriptor.elementsCount) { descriptor.getElementName(it) })
                StructureKind.LIST -> buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0), visiting))
                }
                StructureKind.MAP -> buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schema(descriptor.getElementDescriptor(1), visiting))
                }
                StructureKind.CLASS, StructureKind.OBJECT -> objectOf(descriptor, visiting)
                SerialKind.CONTEXTUAL, is PolymorphicKind -> JsonObject(emptyMap())
            }
        }

        private fun objectOf(descriptor: SerialDescriptor, visiting: Set<String>): JsonObject {
            val name = descriptor.serialName.removeSuffix("?")
            if (name in visiting) return typed("object")
            if (descriptor.elementsCount == 0) {
                return buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                }
            }
            val inside = visiting + name
            val required = (0 until descriptor.elementsCount).filter { requireAll || !descriptor.isElementOptional(it) }
            return buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for (i in 0 until descriptor.elementsCount) {
                        put(descriptor.getElementName(i), element(descriptor, i, inside))
                    }
                }
                if (required.isNotEmpty()) {
                    putJsonArray("required") { required.forEach { add(descriptor.getElementName(it)) } }
                }
            }
        }

        /** Schema of element [index] of [parent], with the bounds of its [IntegerRange], if any. */
        @OptIn(ExperimentalSerializationApi::class)
        private fun element(parent: SerialDescriptor, index: Int, visiting: Set<String>): JsonObject {
            val descriptor = parent.getElementDescriptor(index)
            val base = schema(descriptor, visiting)
            val range = parent.getElementAnnotations(index).filterIsInstance<IntegerRange>().firstOrNull() ?: return base
            val name = "${parent.serialName}.${parent.getElementName(index)}"
            require(descriptor.kind in INTEGER_KINDS) { "@IntegerRange on $name, which is not an integer" }
            return JsonObject(base + mapOf("minimum" to JsonPrimitive(range.min), "maximum" to JsonPrimitive(range.max)))
        }

        private fun typed(type: String) = buildJsonObject { put("type", type) }

        private fun base64() = buildJsonObject {
            put("type", "string")
            put("contentEncoding", "base64")
        }

        private fun enumOf(values: List<String>) = buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(it) } }
        }

        private fun nullable(base: JsonObject): JsonObject {
            val type = base["type"] as? JsonPrimitive
            return if (base.keys == setOf("type") && type != null) {
                buildJsonObject { put("type", JsonArray(listOf(type, JsonPrimitive("null")))) }
            } else {
                buildJsonObject {
                    put("anyOf", buildJsonArray {
                        add(base)
                        add(typed("null"))
                    })
                }
            }
        }
    }
}
