package eus.bihurri.kora.sample.wearcheck

import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.media.ArtworkImage
import eus.bihurri.kora.contract.media.ArtworkMimeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the watch judges the artwork answers it got from the phone. */
class ArtworkChecksTest {
    private val edge = ArtworkChecks.EDGE_PX

    private fun image(mime: ArtworkMimeType = ArtworkMimeType.PNG, px: Int = edge, bytes: Int = 1_234) =
        ArtworkImage(ByteArray(bytes), mime, widthPx = px, heightPx = px)

    @Test
    fun `an image within the requested edge passes and reports mime, size, bytes and time`() {
        val (ok, detail) = ArtworkChecks.evaluate(image(), requestedEdgePx = edge, elapsedMs = 87)

        assertTrue(ok, detail)
        assertEquals("mime=image/png px=${edge}x$edge bytes=1234 ms=87", detail)
    }

    @Test
    fun `a smaller image passes, since a provider never upscales`() {
        assertTrue(ArtworkChecks.evaluate(image(px = 200), requestedEdgePx = edge, elapsedMs = 1).first)
    }

    @Test
    fun `an image larger than requested fails`() {
        val (ok, detail) = ArtworkChecks.evaluate(image(px = edge + 1), requestedEdgePx = edge, elapsedMs = 1)

        assertFalse(ok, detail)
    }

    @Test
    fun `an unknown mime type fails`() {
        assertFalse(ArtworkChecks.evaluate(image(mime = ArtworkMimeType.UNKNOWN), requestedEdgePx = edge, elapsedMs = 1).first)
    }

    @Test
    fun `the unknown id step passes on invalid_payload`() {
        val refused = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, "Unknown artwork 'x'"))

        val (ok, detail) = ArtworkChecks.evaluateUnknown(Result.failure(refused), elapsedMs = 12)

        assertTrue(ok, detail)
        assertEquals("error=invalid_payload ms=12", detail)
    }

    @Test
    fun `the unknown id step fails on an image or on any other error`() {
        assertFalse(ArtworkChecks.evaluateUnknown(Result.success(image()), elapsedMs = 1).first)
        val unauthorized = KoraException(KoraError(ErrorCode.UNAUTHORIZED, "no"))
        assertFalse(ArtworkChecks.evaluateUnknown(Result.failure(unauthorized), elapsedMs = 1).first)
        assertFalse(ArtworkChecks.evaluateUnknown(Result.failure(IllegalStateException("boom")), elapsedMs = 1).first)
    }
}
