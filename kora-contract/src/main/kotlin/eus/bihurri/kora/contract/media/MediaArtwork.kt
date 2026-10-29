package eus.bihurri.kora.contract.media

import eus.bihurri.kora.contract.BytesSerializer
import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.IntegerRange
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * Request of [MediaArtwork.GetArtwork]: the image [artworkId] refers to, with its longer edge at most [maxEdgePx]
 * pixels.
 *
 * [artworkId] is the opaque [TrackSummary.artworkId] or [PlaylistSummary.artworkId] of the same provider; it must not be
 * blank. [maxEdgePx] is within [MediaArtwork.MIN_EDGE_PX]..[MediaArtwork.MAX_EDGE_PX] and defaults to
 * [MediaArtwork.DEFAULT_EDGE_PX].
 */
@Serializable
public data class ArtworkRequest(
    public val artworkId: String,
    @IntegerRange(min = MediaArtwork.MIN_EDGE_PX.toLong(), max = MediaArtwork.MAX_EDGE_PX.toLong())
    public val maxEdgePx: Int = MediaArtwork.DEFAULT_EDGE_PX,
) {
    init {
        require(artworkId.isNotBlank()) { "artworkId must not be blank" }
        require(maxEdgePx in MediaArtwork.MIN_EDGE_PX..MediaArtwork.MAX_EDGE_PX) {
            "maxEdgePx must be within ${MediaArtwork.MIN_EDGE_PX}..${MediaArtwork.MAX_EDGE_PX}: $maxEdgePx"
        }
    }
}

/**
 * Encoding of an [ArtworkImage]. The wire names are the standard MIME types rather than snake_case, so a consumer
 * can hand them on unchanged (for example as the `mimeType` of an MCP image). A newer provider may use a type this
 * version lacks, which reads as [UNKNOWN]: a consumer that cannot show it treats the artwork as absent.
 */
@Serializable(with = ArtworkMimeType.Serializer::class)
public enum class ArtworkMimeType(override val wireName: String) : WireEnum {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),

    /** A type this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<ArtworkMimeType>(
        "eus.bihurri.kora.contract.media.ArtworkMimeType",
        entries,
        UNKNOWN,
    )
}

/**
 * Answer of [MediaArtwork.GetArtwork]: the encoded image [bytes] in [mimeType], [widthPx] by [heightPx] pixels.
 *
 * [bytes] hold between 1 and [MediaArtwork.MAX_ARTWORK_BYTES] bytes; they travel as a CBOR byte string between peers
 * and as Base64 in JSON ([BytesSerializer]). Equality compares the content of [bytes].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public class ArtworkImage(
    @ByteString
    @Serializable(with = BytesSerializer::class)
    public val bytes: ByteArray,
    public val mimeType: ArtworkMimeType,
    public val widthPx: Int,
    public val heightPx: Int,
) {
    init {
        require(bytes.isNotEmpty()) { "An artwork image is not empty" }
        require(bytes.size <= MediaArtwork.MAX_ARTWORK_BYTES) {
            "An artwork image holds at most ${MediaArtwork.MAX_ARTWORK_BYTES} bytes: ${bytes.size}"
        }
        require(widthPx > 0 && heightPx > 0) { "Dimensions must be positive: ${widthPx}x$heightPx" }
    }

    override fun equals(other: Any?): Boolean =
        other is ArtworkImage &&
            mimeType == other.mimeType &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        return result
    }

    override fun toString(): String = "ArtworkImage(${mimeType.wireName}, ${widthPx}x$heightPx, ${bytes.size} bytes)"
}

/**
 * Resolves the opaque `artworkId` of [TrackSummary.artworkId] and [PlaylistSummary.artworkId] into image bytes.
 *
 * - [GetArtwork] answers the image scaled so its longer edge is at most [ArtworkRequest.maxEdgePx] (a provider never
 *   upscales a smaller original) and encoded in at most [MAX_ARTWORK_BYTES] bytes. A provider that cannot fit the
 *   requested size under the cap scales further down or compresses harder; the answer's [ArtworkImage.widthPx] and
 *   [ArtworkImage.heightPx] say what it sent. Full-resolution originals, chunked transfers, file paths and URIs are
 *   deliberately out of scope.
 * - An id the provider does not know (or no longer has) fails with `invalid_payload`, as unknown playlist and track
 *   ids do in [MediaLibrary] and [MediaPlayback] providers. A caller that holds an id from an older listing treats that
 *   as "no artwork".
 * - Artwork has its own scope, [ReadScope]: a grant of `media.library.read` does not cover it.
 *
 * **Why the cap is [MAX_ARTWORK_BYTES] (90 KiB).** Every Kora transport carries at most 256 KiB (262 144 bytes) per
 * message: Binder envelopes, wear frames and the answers of the MCP gate. An MCP tool answer can hold the bytes twice
 * in Base64 (an `image` content block and `structuredContent`), and Base64 is 4/3 the size of its input:
 * - JSON: 92 160 bytes are 122 880 Base64 characters; two copies are 245 760 bytes, plus 4 KiB for the JSON-RPC
 *   envelope and the other fields 249 856, which leaves 12 288 bytes of margin. 96 KiB would not fit: its two Base64
 *   copies alone are exactly 256 KiB.
 * - CBOR: the byte string adds a 5-byte header, and the envelope a few dozen bytes, about 92.3 KiB in all.
 *
 * Version history:
 * - 1.0: [GetArtwork].
 */
public object MediaArtwork : Capability(CapabilityId("media.artwork"), CapabilityVersion(1, 0)) {
    /** Largest encoded image of an [ArtworkImage]: 90 KiB (see the arithmetic above). */
    public const val MAX_ARTWORK_BYTES: Int = 90 * 1024

    /** Smallest [ArtworkRequest.maxEdgePx]: 16 px, an icon. */
    public const val MIN_EDGE_PX: Int = 16

    /** Largest [ArtworkRequest.maxEdgePx]: 1024 px, about what [MAX_ARTWORK_BYTES] holds as a decent JPEG or WebP. */
    public const val MAX_EDGE_PX: Int = 1024

    /** [ArtworkRequest.maxEdgePx] when the caller does not choose: 512 px, a cover on a phone screen. */
    public const val DEFAULT_EDGE_PX: Int = 512

    public val ReadScope: Scope = Scope("media.artwork.read")

    public val GetArtwork: OperationSpec<ArtworkRequest, ArtworkImage> = operation(
        name = "get_artwork",
        input = ArtworkRequest.serializer(),
        output = ArtworkImage.serializer(),
        scope = ReadScope,
        description = "Get the cover image an artworkId of a track or playlist refers to, scaled so its longer edge " +
            "is at most maxEdgePx pixels ($MIN_EDGE_PX-$MAX_EDGE_PX, default $DEFAULT_EDGE_PX) and at most " +
            "$MAX_ARTWORK_BYTES bytes.",
    )
}
