package com.diffuse.core.ai

import android.graphics.Bitmap

/**
 * Binary `ALPHA_8` helpers shared by the fakes and by the tests that assert on their output.
 * specs/ai_provider.md §6 requires the fakes to be deterministic, so everything here is
 * plain integer arithmetic with no anti-aliasing.
 */
object MaskBitmaps {

    const val OPAQUE: Int = 255
    const val CLEAR: Int = 0

    fun empty(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)

    /** A filled circle in [OPAQUE], everything else [CLEAR]. Centre and radius are in pixels. */
    fun circle(width: Int, height: Int, centreX: Float, centreY: Float, radius: Float): Bitmap {
        val bitmap = empty(width, height)
        forEachPixel(bitmap) { x, y ->
            if (inCircle(x, y, centreX, centreY, radius)) OPAQUE else CLEAR
        }
        return bitmap
    }

    /** Punches a [CLEAR] circle out of [bitmap] in place. */
    fun subtractCircle(bitmap: Bitmap, centreX: Float, centreY: Float, radius: Float) {
        forEachPixel(bitmap) { x, y ->
            val current = alphaAt(bitmap, x, y)
            if (inCircle(x, y, centreX, centreY, radius)) CLEAR else current
        }
    }

    fun alphaAt(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y) ushr 24

    /** Fraction of pixels that are opaque. */
    fun coverage(bitmap: Bitmap): Float {
        var opaque = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (alphaAt(bitmap, x, y) == OPAQUE) opaque++
            }
        }
        return opaque.toFloat() / (bitmap.width * bitmap.height)
    }

    private inline fun forEachPixel(bitmap: Bitmap, alpha: (x: Int, y: Int) -> Int) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, alpha(x, y) shl 24)
            }
        }
    }

    private fun inCircle(x: Int, y: Int, centreX: Float, centreY: Float, radius: Float): Boolean {
        val dx = x - centreX
        val dy = y - centreY
        return dx * dx + dy * dy <= radius * radius
    }
}
