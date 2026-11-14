package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType

/** How [CheckRunner] judges the `artwork` and `artwork_unknown` steps. Pure, so it is unit-tested on the JVM. */
internal object ArtworkChecks {
    /** The edge the watch asks for: about what a round watch face shows. */
    const val EDGE_PX = 384

    /** An id no device of the check knows. */
    const val UNKNOWN_ID = "art-unknown"

    /** Passes for a known image type within [requestedEdgePx] (a provider never upscales). */
    fun evaluate(image: ArtworkImage, requestedEdgePx: Int, elapsedMs: Long): Pair<Boolean, String> {
        val ok = image.mimeType != ArtworkMimeType.UNKNOWN &&
            image.widthPx <= requestedEdgePx &&
            image.heightPx <= requestedEdgePx
        return ok to "mime=${image.mimeType.wireName} px=${image.widthPx}x${image.heightPx} bytes=${image.bytes.size} ms=$elapsedMs"
    }

    /** Passes only when the unknown id was refused with `invalid_payload`. */
    fun evaluateUnknown(result: Result<ArtworkImage>, elapsedMs: Long): Pair<Boolean, String> {
        val error = result.exceptionOrNull()
            ?: return false to "unexpected image ${result.getOrThrow()} ms=$elapsedMs"
        val code = (error as? KoraException)?.error?.code
        val detail = code?.let { "error=${it.wireName}" } ?: "error=${error.javaClass.simpleName}: ${error.message}"
        return (code == ErrorCode.INVALID_PAYLOAD) to "$detail ms=$elapsedMs"
    }
}
