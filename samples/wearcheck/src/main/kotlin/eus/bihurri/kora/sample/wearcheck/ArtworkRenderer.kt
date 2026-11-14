package eus.bihurri.kora.sample.wearcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/** Draws the check's artwork: a flat square of the artwork's colour with a white disc, a few kilobytes as PNG. */
internal object ArtworkRenderer {
    /** A PNG of [edgePx] by [edgePx] pixels on [colour] (opaque ARGB). */
    fun renderPng(colour: Int, edgePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(edgePx, edgePx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(colour)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            canvas.drawCircle(edgePx / 2f, edgePx / 2f, edgePx * DISC_RADIUS, paint)
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) { "PNG encoding failed" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val DISC_RADIUS = 0.3f

    /** Ignored by PNG (lossless); the value is required by the API. */
    private const val PNG_QUALITY = 100
}
