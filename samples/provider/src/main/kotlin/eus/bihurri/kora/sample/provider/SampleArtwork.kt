package eus.bihurri.kora.sample.provider

import eus.bihurri.kora.contract.media.MediaArtwork

/**
 * Which artwork ids the sample knows and how large it sends them. Pure (no Android types), so it is unit-tested on the
 * JVM; [ArtworkRenderer] draws the pixels.
 *
 * Every sample track `t-<n>` (n >= 1, including the endless ones of `now_playing_changed`) has artwork
 * [trackArtworkId], and every playlist in [playlistIds] has [playlistArtworkId]. The ids are opaque to consumers; the
 * sample only uses a readable shape to tell them apart. Anything else is unknown, which the provider answers with
 * `invalid_payload`.
 */
internal class SampleArtwork(private val playlistIds: Set<String>) {
    /** Background colour (opaque ARGB) of [artworkId], or null when the sample has no such artwork. */
    fun colourOf(artworkId: String): Int? {
        TRACK_ID.matchEntire(artworkId)?.let { match ->
            val n = match.groupValues[1].toIntOrNull() ?: return null
            return if (n >= 1) PALETTE[n % PALETTE.size] else null
        }
        if (!artworkId.startsWith(PLAYLIST_PREFIX)) return null
        val playlistId = artworkId.removePrefix(PLAYLIST_PREFIX)
        return if (playlistId in playlistIds) PLAYLIST_COLOUR else null
    }

    /** An image encoded at [edgePx] by [edgePx] pixels. */
    class Encoded(val edgePx: Int, val bytes: ByteArray)

    companion object {
        /** The edge of the sample's "original" artwork: a request for more gets this, never an upscaled image. */
        const val NATIVE_EDGE_PX: Int = 640

        private const val TRACK_PREFIX = "art-t-"
        private const val PLAYLIST_PREFIX = "art-"
        private val TRACK_ID = Regex("""art-t-(\d{1,9})""")
        private const val PLAYLIST_COLOUR: Int = 0xFF37474F.toInt()
        private val PALETTE: IntArray = intArrayOf(
            0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFF1565C0.toInt(), 0xFF00838F.toInt(),
            0xFF2E7D32.toInt(), 0xFFF9A825.toInt(), 0xFFEF6C00.toInt(), 0xFF4E342E.toInt(),
        )

        fun trackArtworkId(n: Int): String = "$TRACK_PREFIX$n"

        fun playlistArtworkId(playlistId: String): String = "$PLAYLIST_PREFIX$playlistId"

        /** First edge to try for a request of [maxEdgePx]: the request, but never above [NATIVE_EDGE_PX]. */
        fun startEdge(maxEdgePx: Int): Int = minOf(maxEdgePx, NATIVE_EDGE_PX)

        /**
         * Encodes at [startEdgePx] and, while the result exceeds [MediaArtwork.MAX_ARTWORK_BYTES], again at three
         * quarters of the edge, never below [MediaArtwork.MIN_EDGE_PX]. Fails with [IllegalStateException] when even
         * the minimum does not fit (it cannot happen with the sample's simple images).
         */
        fun encodeUnderCap(startEdgePx: Int, encode: (edgePx: Int) -> ByteArray): Encoded {
            var edge = startEdgePx
            while (true) {
                val bytes = encode(edge)
                if (bytes.size <= MediaArtwork.MAX_ARTWORK_BYTES) return Encoded(edge, bytes)
                check(edge > MediaArtwork.MIN_EDGE_PX) {
                    "Artwork exceeds ${MediaArtwork.MAX_ARTWORK_BYTES} bytes even at ${MediaArtwork.MIN_EDGE_PX} px"
                }
                edge = maxOf(MediaArtwork.MIN_EDGE_PX, edge * 3 / 4)
            }
        }

        /** The colour of the disc drawn on [background]: halfway to white, opaque. */
        fun discColour(background: Int): Int {
            fun lighter(channel: Int) = channel + (0xFF - channel) / 2
            val red = lighter(background shr 16 and 0xFF)
            val green = lighter(background shr 8 and 0xFF)
            val blue = lighter(background and 0xFF)
            return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
}
