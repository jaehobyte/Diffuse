package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/** specs/selection_tool.md §8.2: `alpha = min(alpha, maskAlpha)` over the whole image. */
internal object CutOutOp {

    private const val ALPHA_SHIFT = 24
    private const val RGB_MASK = 0x00FFFFFF

    fun apply(source: Bitmap, mask: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        // Nearest neighbour: the mask is binary and must stay that way.
        val scaled = mask.scaledTo(width, height, filter = false)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val maskRow = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            scaled.getPixels(maskRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val existing = pixel ushr ALPHA_SHIFT
                val allowed = maskRow[x] ushr ALPHA_SHIFT
                row[x] = (minOf(existing, allowed) shl ALPHA_SHIFT) or (pixel and RGB_MASK)
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        if (scaled !== mask) scaled.recycle()
        return out
    }
}
