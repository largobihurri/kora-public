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
     * holds the Base64 twice (the text and `structuredContent`), so it fits the same limit. Output that does not
     * decode as an [ArtworkImage] falls back to [JsonText] too; the gate only produces valid ones.
     */
    data object Artwork : ToolContent {
        override fun render(structured: JsonElement, json: Json): JsonArray {
            val image = try {
                json.decodeFromJsonElement(ArtworkImage.serializer(), structured)
            } catch (_: IllegalArgumentException) {
                return JsonText.render(structured, json)
            }
            val data = (structured as JsonObject)["bytes"]
            if (image.mimeType == ArtworkMimeType.UNKNOWN || data !is JsonPrimitive) return JsonText.render(structured, json)
            val type = image.mimeType.wireName
            val imageBlock = buildJsonObject {
                put("type", "image")
                put("data", data)
                put("mimeType", type)
            }
            val metadata = "Artwork $type, ${image.widthPx}x${image.heightPx} px, ${image.bytes.size} bytes " +
                "(Base64 in the image block and in structuredContent.bytes)."
            return JsonArray(listOf(imageBlock, textBlock(metadata)))
        }
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
