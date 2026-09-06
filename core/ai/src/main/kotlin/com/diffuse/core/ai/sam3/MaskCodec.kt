package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64

/**
 * specs/segmentation.md §1. The server's `format = "png"` is PIL mode `L` — 8-bit grayscale,
 * `mask * 255` — despite api.md calling it an alpha PNG. It therefore decodes to an *opaque*
 * bitmap whose luminance is the mask, so the alpha channel would read 255 everywhere.
 */
internal object MaskCodec {

    private const val THRESHOLD = 128
    private const val OPAQUE = 255
    private const val ALPHA_SHIFT = 24

    /** @return an `ALPHA_8` bitmap, strictly binary, at the PNG's own size. */
    fun decode(base64Png: String): Bitmap {
        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("mask PNG did not decode")
        return try {
            toBinaryAlpha(decoded)
        } finally {
            decoded.recycle()
        }
    }

    private fun toBinaryAlpha(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                // A grayscale PNG decodes with R == G == B, so one channel is the whole story.
                val set = Color.red(pixel) >= THRESHOLD
                out.setPixel(x, y, if (set) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        return out
    }
}
