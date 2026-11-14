package eus.bihurri.kora.mcp

import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.MediaArtwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * How the `content` blocks of a successful `tools/call` result are made from its `structuredContent`. Chosen once per
 * operation when its [McpTool] is built ([forOperation]); `structuredContent` itself is never changed and always
 * conforms to the tool's `outputSchema`, also when a wire enum carries the `unknown` fallback ([JsonSchemas.output]).
 *
 * - [JsonText], every tool but one: a single `text` block holding the JSON of `structuredContent`, for clients that
 *   ignore structured content.
 * - [Artwork], for [MediaArtwork.GetArtwork]: an `image` block with the Base64 bytes and the MIME type, plus a short
 *   metadata `text` block without the bytes. Repeating the JSON as text would put the Base64 in the answer a third
 *   time; with the image block it is there exactly twice (the image and `structuredContent.bytes`), which is what
 *   [MediaArtwork.MAX_ARTWORK_BYTES] is sized for, so a maximum-size artwork answer stays under the 256 KiB limit of
 *   the MCP gate.
 *
 * The rule is keyed by the operation spec on purpose: explicit, no reflection, and no MCP concept in the contract.
 */
internal sealed interface ToolContent {
    /** The `content` array for [structured], the `structuredContent` of the result; [json] encodes text blocks. */
    fun render(structured: JsonElement, json: Json): JsonArray

    /** The default: one `text` block with the JSON of `structuredContent`. */
    data object JsonText : ToolContent {
        override fun render(structured: JsonElement, json: Json): JsonArray =
            JsonArray(listOf(textBlock(json.encodeToString(JsonElement.serializer(), structured))))
    }

    /**
     * An [ArtworkImage] as an `image` block plus a metadata `text` block (MIME type, size in pixels, byte count).
     *
     * The `data` of the image is the `bytes` string of `structuredContent` as is: standard Base64 with padding, the
     * form MCP expects (`BytesSerializer`). An image block needs a real image MIME type, so when the type is
     * [ArtworkMimeType.UNKNOWN] (a newer provider's encoding this version cannot name) the result falls back to
     * [JsonText]: the agent still gets the bytes and dimensions, with `"mimeType":"unknown"`, and the answer still
     * holds the Base64 twice (the text and `structuredContent`), so it fits the same limit. Output without the
     * [ArtworkImage] fields falls back to [JsonText] too; the gate only produces valid ones.
     *
     * The metadata is read from the JSON fields, and the byte count derived from the Base64 length, so the image is
     * never decoded just to describe it. The fields are still checked: non-empty padded standard Base64, a known MIME
     * type and positive dimensions, or the result falls back to [JsonText].
     */
    data object Artwork : ToolContent {
        override fun render(structured: JsonElement, json: Json): JsonArray {
            val fields = structured as? JsonObject ?: return JsonText.render(structured, json)
            val data = (fields["bytes"] as? JsonPrimitive)?.takeIf { it.isString && PADDED_BASE64.matches(it.content) }
            val type = (fields["mimeType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { wire -> ArtworkMimeType.entries.any { it != ArtworkMimeType.UNKNOWN && it.wireName == wire } }
            val width = (fields["widthPx"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            val height = (fields["heightPx"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            if (data == null || type == null || width == null || height == null) return JsonText.render(structured, json)
            val imageBlock = buildJsonObject {
                put("type", "image")
                put("data", data)
                put("mimeType", type)
            }
            val metadata = "Artwork $type, ${width}x$height px, ${decodedSize(data.content)} bytes " +
                "(Base64 in the image block and in structuredContent.bytes)."
            return JsonArray(listOf(imageBlock, textBlock(metadata)))
        }

        /** Non-empty padded standard Base64, the `BytesSerializer` form; anything else falls back to [JsonText]. */
        private val PADDED_BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{4})")

        /** The byte count of [base64], already checked against [PADDED_BASE64], without decoding it. */
        private fun decodedSize(base64: String): Int = base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
    }

    companion object {
        /** The rendering of [spec]'s results: [Artwork] for [MediaArtwork.GetArtwork], [JsonText] otherwise. */
        fun forOperation(spec: OperationSpec<*, *>): ToolContent =
            if (spec === MediaArtwork.GetArtwork) Artwork else JsonText

        /** A `text` content block. */
        fun textBlock(text: String): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
        }
    }
}
