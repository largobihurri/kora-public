package eus.bihurri.kora.sample.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Draws the sample's artwork: a square of the artwork's colour with a lighter disc and a centre hole, like a record.
 * Flat colours keep the PNG a few kilobytes at any size the contract allows. A real app scales its own cover images
 * instead (and sends photos as JPEG, see `docs/app-handoff.md`).
 */
internal object ArtworkRenderer {
    /** A PNG of [edgePx] by [edgePx] pixels on [colour] (opaque ARGB). */
    fun renderPng(colour: Int, edgePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(edgePx, edgePx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val centre = edgePx / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(colour)
            paint.color = SampleArtwork.discColour(colour)
            canvas.drawCircle(centre, centre, edgePx * DISC_RADIUS, paint)
            paint.color = colour
            canvas.drawCircle(centre, centre, edgePx * HOLE_RADIUS, paint)
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val DISC_RADIUS = 0.38f
    private const val HOLE_RADIUS = 0.08f

    /** Ignored by PNG (lossless); the value is required by the API. */
    private const val PNG_QUALITY = 100
}
