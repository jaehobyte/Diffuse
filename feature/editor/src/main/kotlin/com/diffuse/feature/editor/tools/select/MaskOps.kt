package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap

/**
 * specs/selection_tool.md §4. Masks are strictly binary, so every operation here is a
 * per-pixel min/max and nothing ever feathers.
 */
object MaskOps {

    const val OPAQUE = 255
    private const val ALPHA_SHIFT = 24

    fun inverted(mask: Bitmap): Bitmap = mapPixels(mask) { set -> !set }

    private inline fun mapPixels(mask: Bitmap, transform: (Boolean) -> Boolean): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                val set = (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0
                out.setPixel(x, y, if (transform(set)) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        return out
    }
}
