package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import eus.bihurri.kora.contract.media.MediaArtwork
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The artwork ids of one device's stubs (the same scheme as the provider sample, copied to keep the samples
 * independent). Track `<role>-t<n>` (n >= 1) has [trackArtworkId] `art-<role>-t<n>` and every playlist in
 * [playlistIds] has [playlistArtworkId] `art-<playlist id>`. Anything else, including the other device's ids, is
 * unknown. Pure (no Android types); [ArtworkRenderer] draws the pixels.
 */
internal class WearArtwork(role: String, private val playlistIds: Set<String>) {
    private val trackId = Regex("""${Regex.escape(PREFIX + role)}-t(\d{1,9})""")

    /** Background colour (opaque ARGB) of [artworkId], or null when this device has no such artwork. */
    fun colourOf(artworkId: String): Int? {
        trackId.matchEntire(artworkId)?.let { match ->
            val n = match.groupValues[1].toIntOrNull() ?: return null
            return if (n >= 1) PALETTE[n % PALETTE.size] else null
        }
        if (!artworkId.startsWith(PREFIX)) return null
        return if (artworkId.removePrefix(PREFIX) in playlistIds) PLAYLIST_COLOUR else null
    }

    /** An image encoded at [edgePx] by [edgePx] pixels. */
    class Encoded(val edgePx: Int, val bytes: ByteArray)

    companion object {
        /** The edge of the "original" artwork: a request for more gets this, never an upscaled image. */
        const val NATIVE_EDGE_PX: Int = 640

        private const val PREFIX = "art-"
        private const val PLAYLIST_COLOUR: Int = 0xFF37474F.toInt()
        private val PALETTE: IntArray = intArrayOf(
            0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFEF6C00.toInt(),
        )

        fun trackArtworkId(role: String, n: Int): String = "$PREFIX$role-t$n"

        fun playlistArtworkId(playlistId: String): String = "$PREFIX$playlistId"

        /**
         * Encodes at `min(maxEdgePx, NATIVE_EDGE_PX)` and, while the result exceeds [MediaArtwork.MAX_ARTWORK_BYTES],
         * again at three quarters of the edge, never below [MediaArtwork.MIN_EDGE_PX].
         */
        fun encodeUnderCap(maxEdgePx: Int, encode: (edgePx: Int) -> ByteArray): Encoded {
            var edge = minOf(maxEdgePx, NATIVE_EDGE_PX)
            while (true) {
                val bytes = encode(edge)
                if (bytes.size <= MediaArtwork.MAX_ARTWORK_BYTES) return Encoded(edge, bytes)
                check(edge > MediaArtwork.MIN_EDGE_PX) {
                    "Artwork exceeds ${MediaArtwork.MAX_ARTWORK_BYTES} bytes even at ${MediaArtwork.MIN_EDGE_PX} px"
                }
                edge = maxOf(MediaArtwork.MIN_EDGE_PX, edge * 3 / 4)
            }
        }
    }
}

/**
 * Provides [MediaArtwork] from [artwork]: `get_artwork` answers a PNG drawn by [render] (`ArtworkRenderer.renderPng`
 * in the app, a fake in JVM tests) under the byte cap; an unknown id fails with `invalid_payload`.
 */
internal fun KoraNode.provideWearArtwork(artwork: WearArtwork, render: (colour: Int, edgePx: Int) -> ByteArray) {
    provide(MediaArtwork) {
        on(MediaArtwork.GetArtwork) { request ->
            val colour = artwork.colourOf(request.artworkId)
                ?: throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown artwork '${request.artworkId}'"))
            withContext(Dispatchers.Default) {
                val encoded = WearArtwork.encodeUnderCap(request.maxEdgePx) { edge -> render(colour, edge) }
                ArtworkImage(encoded.bytes, ArtworkMimeType.PNG, widthPx = encoded.edgePx, heightPx = encoded.edgePx)
            }
        }
    }
}
