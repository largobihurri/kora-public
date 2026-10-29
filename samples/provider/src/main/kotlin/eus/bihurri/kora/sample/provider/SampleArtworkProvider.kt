package eus.bihurri.kora.sample.provider

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
 * Provides [MediaArtwork] from [artwork]: `get_artwork` answers a PNG drawn by [render] (`ArtworkRenderer.renderPng`
 * in the app, a fake in JVM tests), sized by [SampleArtwork.startEdge] and [SampleArtwork.encodeUnderCap]. An unknown
 * id fails with `invalid_payload`.
 */
internal fun KoraNode.provideSampleArtwork(artwork: SampleArtwork, render: (colour: Int, edgePx: Int) -> ByteArray) {
    provide(MediaArtwork) {
        on(MediaArtwork.GetArtwork) { request ->
            val colour = artwork.colourOf(request.artworkId)
                ?: throw KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown artwork '${request.artworkId}'"))
            // A few milliseconds of drawing and PNG encoding, kept off the caller's thread.
            withContext(Dispatchers.Default) {
                val encoded = SampleArtwork.encodeUnderCap(SampleArtwork.startEdge(request.maxEdgePx)) { edge ->
                    render(colour, edge)
                }
                ArtworkImage(encoded.bytes, ArtworkMimeType.PNG, widthPx = encoded.edgePx, heightPx = encoded.edgePx)
            }
        }
    }
}
