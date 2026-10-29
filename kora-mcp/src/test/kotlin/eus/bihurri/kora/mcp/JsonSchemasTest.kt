package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.BytesSerializer
import eus.bihurri.kora.contract.IntegerRange
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import eus.bihurri.kora.contract.fitness.ActiveSession
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackState
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonSchemasTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun JsonObject.properties() = this["properties"]!!.jsonObject

    private fun JsonObject.required() = this["required"]?.jsonArray?.map { it.jsonPrimitive.content }

    @Serializable
    private data class Inner(val label: String, val weight: Float = 1f)

    @Serializable
    private data class Outer(
        val id: Long,
        val flag: Boolean,
        val inner: Inner,
        val tags: List<String>,
        val counts: Map<String, Int> = emptyMap(),
        val note: String? = null,
        val maybe: Inner? = null,
        val fixed: Double = 0.0,
    )

    @Serializable
    private data class Node(val name: String, val next: Node? = null)

    @Serializable(with = Colour.Serializer::class)
    private enum class Colour(override val wireName: String) : WireEnum {
        RED("red"),
        BLUE("blue"),
        UNKNOWN("unknown"),
        ;

        object Serializer : TolerantEnumSerializer<Colour>("test.mcp.Colour", entries, UNKNOWN)
    }

    @Serializable
    private data class Painted(val colour: Colour, val other: Colour? = null)

    @Serializable
    private data class Palette(val main: Painted, val history: List<Colour>, val spare: Painted? = null)

    @Serializable
    private enum class Plain { ONE, TWO }

    @Serializable
    private data class Limited(
        @IntegerRange(min = 1, max = 10) val count: Int,
        @IntegerRange(min = -5, max = 5) val offset: Long? = null,
        val free: Int = 0,
    )

    @Serializable
    private data class MisusedRange(@IntegerRange(min = 0, max = 1) val name: String)

    @Test
    fun `primitives map to their JSON types`() {
        assertEquals(json("""{"type":"string"}"""), JsonSchemas.of(String.serializer().descriptor))
        assertEquals(json("""{"type":"boolean"}"""), JsonSchemas.of(Boolean.serializer().descriptor))
        for (integer in listOf(Int.serializer().descriptor, Long.serializer().descriptor, Short.serializer().descriptor)) {
            assertEquals(json("""{"type":"integer"}"""), JsonSchemas.of(integer))
        }
        assertEquals(json("""{"type":"number"}"""), JsonSchemas.of(Float.serializer().descriptor))
        assertEquals(json("""{"type":"number"}"""), JsonSchemas.of(Double.serializer().descriptor))
    }

    @Test
    fun `a class lists its properties and requires those without defaults`() {
        val schema = JsonSchemas.of(Outer.serializer().descriptor)

        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("id", "flag", "inner", "tags", "counts", "note", "maybe", "fixed"), schema.properties().keys.toList())
        assertEquals(listOf("id", "flag", "inner", "tags"), schema.required())
        assertEquals(json("""{"type":"integer"}"""), schema.properties()["id"])
        assertEquals(json("""{"type":"array","items":{"type":"string"}}"""), schema.properties()["tags"])
        assertEquals(
            json("""{"type":"object","additionalProperties":{"type":"integer"}}"""),
            schema.properties()["counts"],
        )
    }

    @Test
    fun `nested classes become nested objects`() {
        val inner = JsonSchemas.of(Outer.serializer().descriptor).properties()["inner"]!!.jsonObject

        assertEquals(listOf("label"), inner.required())
        assertEquals(json("""{"type":"number"}"""), inner.properties()["weight"])
    }

    @Test
    fun `nullable primitives use a type array and other nullables use anyOf`() {
        val properties = JsonSchemas.of(Outer.serializer().descriptor).properties()

        assertEquals(json("""{"type":["string","null"]}"""), properties["note"])
        val maybe = properties["maybe"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(2, maybe.size)
        assertEquals(listOf("label"), maybe[0].jsonObject.required())
        assertEquals(json("""{"type":"null"}"""), maybe[1])
    }

    @Test
    fun `a class without properties is closed`() {
        val expected = json("""{"type":"object","additionalProperties":false}""")
        assertEquals(expected, JsonSchemas.of(NoInput.serializer().descriptor))
        assertEquals(expected, JsonSchemas.of(Unit.serializer().descriptor))
    }

    @Test
    fun `wire enums list their values without the unknown fallback`() {
        val schema = JsonSchemas.of(Painted.serializer().descriptor)

        assertEquals(json("""{"type":"string","enum":["red","blue"]}"""), schema.properties()["colour"])
        val other = schema.properties()["other"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(json("""{"type":"string","enum":["red","blue"]}"""), other[0])
        assertEquals(listOf("colour"), schema.required())
    }

    @Test
    fun `input schemas exclude the unknown fallback at every position`() {
        val writable = json("""{"type":"string","enum":["red","blue"]}""")
        val schema = JsonSchemas.input(Palette.serializer().descriptor)

        assertEquals(listOf(writable), enumsIn(schema).distinct())
        assertEquals(5, enumsIn(schema).size)
        assertEquals(listOf("main", "history"), schema.required())
        assertEquals(JsonSchemas.of(Palette.serializer().descriptor), schema)
    }

    @Test
    fun `output schemas admit the unknown fallback last at every position`() {
        val readable = json("""{"type":"string","enum":["red","blue","unknown"]}""")
        val schema = JsonSchemas.output(Palette.serializer().descriptor)

        val main = schema.properties()["main"]!!.jsonObject.properties()
        assertEquals(readable, main["colour"])
        assertEquals(json("""{"anyOf":[{"type":"string","enum":["red","blue","unknown"]},{"type":"null"}]}"""), main["other"])
        assertEquals(json("""{"type":"array","items":{"type":"string","enum":["red","blue","unknown"]}}"""), schema.properties()["history"])
        val spare = schema.properties()["spare"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject
        assertEquals(readable, spare.properties()["colour"])
        assertEquals(listOf(readable), enumsIn(schema).distinct())
        assertEquals(5, enumsIn(schema).size)
        assertEquals(listOf("main", "history", "spare"), schema.required())
        assertEquals(readable, JsonSchemas.output(Colour.serializer().descriptor))
        assertEquals(json("""{"type":"string","enum":["red","blue"]}"""), JsonSchemas.input(Colour.serializer().descriptor))
    }

    @Test
    fun `plain enums have no fallback in either direction`() {
        val expected = json("""{"type":"string","enum":["ONE","TWO"]}""")
        assertEquals(expected, JsonSchemas.input(Plain.serializer().descriptor))
        assertEquals(expected, JsonSchemas.output(Plain.serializer().descriptor))
    }

    /** Every schema in [element] that carries an `enum`, depth first. */
    private fun enumsIn(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> (if ("enum" in element) listOf(element) else emptyList()) + element.values.flatMap { enumsIn(it) }
        is JsonArray -> element.flatMap { enumsIn(it) }
        else -> emptyList()
    }

    @Test
    fun `binary fields are Base64 strings`() {
        val expected = json("""{"type":"string","contentEncoding":"base64"}""")
        assertEquals(expected, JsonSchemas.of(BytesSerializer.descriptor))
        assertEquals(json("""{"anyOf":[{"type":"string","contentEncoding":"base64"},{"type":"null"}]}"""), JsonSchemas.of(BytesSerializer.nullable.descriptor))

        val image = JsonSchemas.output(ArtworkImage.serializer().descriptor)
        assertEquals(expected, image.properties()["bytes"])
        assertEquals(json("""{"type":"string","enum":["image/jpeg","image/png","image/webp","unknown"]}"""), image.properties()["mimeType"])
        assertEquals(listOf("bytes", "mimeType", "widthPx", "heightPx"), image.required())
    }

    @Test
    fun `integer ranges become minimum and maximum`() {
        val properties = JsonSchemas.of(Limited.serializer().descriptor).properties()

        assertEquals(json("""{"type":"integer","minimum":1,"maximum":10}"""), properties["count"])
        assertEquals(json("""{"type":["integer","null"],"minimum":-5,"maximum":5}"""), properties["offset"])
        assertEquals(json("""{"type":"integer"}"""), properties["free"])
    }

    @Test
    fun `an integer range on a non-integer property is rejected`() {
        val error = assertThrows<IllegalArgumentException> { JsonSchemas.of(MisusedRange.serializer().descriptor) }
        assertTrue(error.message!!.contains("name"), error.message)
    }

    @Test
    fun `plain enums list their constant names`() {
        assertEquals(json("""{"type":"string","enum":["ONE","TWO"]}"""), JsonSchemas.of(Plain.serializer().descriptor))
    }

    @Test
    fun `lists maps and nullable lists compose`() {
        assertEquals(
            json("""{"type":"array","items":{"type":"array","items":{"type":"integer"}}}"""),
            JsonSchemas.of(ListSerializer(ListSerializer(Int.serializer())).descriptor),
        )
        assertEquals(
            json("""{"type":"object","additionalProperties":{"type":"boolean"}}"""),
            JsonSchemas.of(MapSerializer(String.serializer(), Boolean.serializer()).descriptor),
        )
        val nullableList = JsonSchemas.of(ListSerializer(String.serializer()).nullable.descriptor)
        assertEquals(
            json("""{"anyOf":[{"type":"array","items":{"type":"string"}},{"type":"null"}]}"""),
            nullableList,
        )
    }

    @Test
    fun `requireAll lists every property because results always carry defaults`() {
        val schema = JsonSchemas.of(Outer.serializer().descriptor, requireAll = true)
        assertEquals(schema.properties().keys.toList(), schema.required())
    }

    @Test
    fun `a recursive class terminates`() {
        val next = JsonSchemas.of(Node.serializer().descriptor).properties()["next"]
        assertEquals(json("""{"type":["object","null"]}"""), next)
    }

    @Test
    fun `generics carry the instantiated element type`() {
        val schema = JsonSchemas.of(Page.serializer(PlaylistSummary.serializer()).descriptor)

        val items = schema.properties()["items"]!!.jsonObject
        assertEquals("array", items["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("id", "name", "trackCount", "artworkId"), items["items"]!!.jsonObject.properties().keys.toList())
        assertEquals(listOf("items", "total"), schema.required())
    }

    @Test
    fun `real contract types`() {
        val request = JsonSchemas.of(PlaylistTracksRequest.serializer().descriptor)
        assertEquals(listOf("playlistId"), request.required())
        assertEquals(listOf("limit", "offset"), request.properties()["page"]!!.jsonObject.properties().keys.toList())
        assertEquals(null, JsonSchemas.of(PageRequest.serializer().descriptor).required())

        val state = JsonSchemas.of(PlaybackState.serializer().descriptor).properties()
        assertEquals(
            JsonArray(listOf("idle", "buffering", "playing", "paused", "ended").map { kotlinx.serialization.json.JsonPrimitive(it) }),
            state["status"]!!.jsonObject["enum"],
        )
        assertFalse(state["status"].toString().contains("unknown"))

        val session = JsonSchemas.of(ActiveSession.serializer().descriptor)
        assertEquals(listOf("sessionId", "activityType", "phase", "startedAtEpochMs"), session.required())
    }
}
