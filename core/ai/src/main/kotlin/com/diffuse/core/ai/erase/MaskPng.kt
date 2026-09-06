package com.diffuse.core.ai.erase

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * specs/generative_erase.md §2. The mask is sent as a PNG **whose alpha channel carries it**:
 * opaque is the region to erase.
 *
 * `Bitmap.compress` will not write an `ALPHA_8` bitmap usefully, so it is converted to
 * `ARGB_8888` first — the same shape `core:imaging`'s `MaskIo` writes to disk.
 */
internal object MaskPng {

    private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    private const val TRANSPARENT = 0
    private const val ALPHA_SHIFT = 24
    private const val PNG_QUALITY = 100

    fun encode(alpha: Bitmap): ByteArray {
        val argb = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until alpha.height) {
                for (x in 0 until alpha.width) {
                    val set = (alpha.getPixel(x, y) ushr ALPHA_SHIFT) != 0
                    argb.setPixel(x, y, if (set) OPAQUE_WHITE else TRANSPARENT)
                }
            }
            val out = ByteArrayOutputStream()
            argb.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            return out.toByteArray()
        } finally {
            argb.recycle()
        }
    }
}
