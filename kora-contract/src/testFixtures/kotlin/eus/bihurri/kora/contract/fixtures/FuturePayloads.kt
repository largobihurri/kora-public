package eus.bihurri.kora.contract.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Simulates payloads written by a **future minor version** by editing the encodings of the current samples, then
 * checks that the current code reads them as it reads the originals. Works on the encoded form, so it covers every
 * type and both formats without a hand-written "future" class per type.
 *
 * - [verifyUnknownFieldsAreIgnored]: every object (every map in CBOR) gains a field this version has never heard of.
 * - [verifyUnknownEnumConstantsReadAsUnknown]: enum-valued fields hold a constant this version does not know, which
 *   must read as the enum's `unknown` entry.
 */
public object FuturePayloads {
    private val json = Json

    public fun verifyUnknownFieldsAreIgnored(cases: List<FixtureCase<*>>) {
        verify(cases) { format, text ->
            when (format) {
                FixtureFormat.JSON -> json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(text).withExtraField())
                FixtureFormat.CBOR -> CborTree.edit(text.fromHex()) { it.withExtraField() }.toHex()
            } to text
        }
    }

    /**
     * Replaces the string value of every field named in [fields] by an unknown constant and expects the same result as
     * the constant `unknown`. Returns how many cases contained such a field, so a test can insist that it did not
     * check nothing.
     */
    public fun verifyUnknownEnumConstantsReadAsUnknown(
        cases: List<FixtureCase<*>>,
        fields: Set<String> = setOf("status", "phase", "code"),
    ): Int {
        var affected = 0
        verify(cases) { format, text ->
            val future: String
            val unknown: String
            when (format) {
                FixtureFormat.JSON -> {
                    val tree = json.parseToJsonElement(text)
                    future = json.encodeToString(JsonElement.serializer(), tree.replaceStrings(fields, "from_the_future"))
                    unknown = json.encodeToString(JsonElement.serializer(), tree.replaceStrings(fields, "unknown"))
                }

                FixtureFormat.CBOR -> {
                    future = CborTree.edit(text.fromHex()) { it.replaceStrings(fields, "from_the_future") }.toHex()
                    unknown = CborTree.edit(text.fromHex()) { it.replaceStrings(fields, "unknown") }.toHex()
                }
            }
            if (format == FixtureFormat.JSON && future != unknown) affected++
            future to unknown
        }
        return affected
    }

    /** [edit] returns the future text and the text whose decoding is the expected result. */
    private fun verify(cases: List<FixtureCase<*>>, edit: (FixtureFormat, String) -> Pair<String, String>) {
        val problems = mutableListOf<String>()
        for (case in cases) {
            for (format in FixtureFormat.entries) {
                val (future, reference) = edit(format, case.encode(format))
                val expected = case.decodeToJson(format, reference)
                val actual = try {
                    case.decodeToJson(format, future)
                } catch (e: Exception) {
                    problems += "$case.${format.extension} from the future does not decode: ${e.message?.lineSequence()?.firstOrNull()}"
                    continue
                }
                if (actual != expected) problems += "$case.${format.extension} from the future reads as $actual instead of $expected"
            }
        }
        if (problems.isNotEmpty()) throw AssertionError("${problems.size} forward-compatibility problem(s):\n - " + problems.joinToString("\n - "))
    }

    private const val FUTURE_FIELD = "field_from_the_future"

    private fun JsonElement.withExtraField(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { it.value.withExtraField() } +
                (FUTURE_FIELD to JsonObject(mapOf("nested" to JsonArray(listOf(JsonPrimitive(1)))))),
        )

        is JsonArray -> JsonArray(map { it.withExtraField() })
        else -> this
    }

    private fun JsonElement.replaceStrings(fields: Set<String>, value: String): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { (key, element) ->
                if (key in fields && element is JsonPrimitive && element.isString) JsonPrimitive(value) else element.replaceStrings(fields, value)
            },
        )

        is JsonArray -> JsonArray(map { it.replaceStrings(fields, value) })
        else -> this
    }

    private fun CborTree.Node.withExtraField(): CborTree.Node = when (this) {
        is CborTree.Arr -> CborTree.Arr(items.map { it.withExtraField() })
        is CborTree.Tag -> CborTree.Tag(head, child.withExtraField())
        // An empty map is how kotlinx.serialization writes a null object, and a future writer still writes null that
        // way: giving it a field would turn "absent" into "present".
        is CborTree.Map -> if (entries.isEmpty()) this else CborTree.Map(
            entries.map { (k, v) -> k to v.withExtraField() } + (CborTree.text(FUTURE_FIELD) to futureValue()),
        )

        is CborTree.Raw -> this
    }

    private fun futureValue(): CborTree.Node =
        CborTree.Map(listOf(CborTree.text("nested") to CborTree.Arr(listOf(CborTree.Raw(byteArrayOf(1))))))

    private fun CborTree.Node.replaceStrings(fields: Set<String>, value: String): CborTree.Node = when (this) {
        is CborTree.Arr -> CborTree.Arr(items.map { it.replaceStrings(fields, value) })
        is CborTree.Tag -> CborTree.Tag(head, child.replaceStrings(fields, value))
        is CborTree.Map -> CborTree.Map(
            entries.map { (k, v) ->
                val key = (k as? CborTree.Raw)?.let(CborTree::textOrNull)
                k to if (key != null && key in fields && CborTree.textOrNull(v) != null) CborTree.text(value) else v.replaceStrings(fields, value)
            },
        )

        is CborTree.Raw -> this
    }
}
