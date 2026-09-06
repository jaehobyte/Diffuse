package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/**
 * specs/selection_tool.md §8.1: `out = lerp(in, adjusted, maskAlpha)`.
 *
 * v2 masks are binary, so this is a select today. It is written as a lerp anyway because that
 * is the contract the spec states, and feathering (a D-level item) then costs nothing here.
 */
internal object MaskBlend {

    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MAX = 255
    private const val CHANNEL_MASK = 0xFF

    /** ARGB_8888 channel offsets, most significant first. */
    private val CHANNEL_SHIFTS = intArrayOf(24, 16, 8, 0)

    /**
     * @param mask `ALPHA_8`, at whatever resolution it was saved; it is scaled to [source]'s
     * size when they differ, because the render resolution is not the preview's.
     */
    fun blend(source: Bitmap, adjusted: Bitmap, mask: Bitmap): Bitmap {
        require(source.width == adjusted.width && source.height == adjusted.height) {
            "the adjusted bitmap must be the source's size"
        }
        val scaled = scaleToMatch(mask, source)
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val sourceRow = IntArray(width)
        val adjustedRow = IntArray(width)
        val outRow = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(sourceRow, 0, width, 0, y, width, 1)
            adjusted.getPixels(adjustedRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val weight = scaled.getPixel(x, y) ushr ALPHA_SHIFT
                outRow[x] = when (weight) {
                    0 -> sourceRow[x]
                    ALPHA_MAX -> adjustedRow[x]
                    else -> lerpPixel(sourceRow[x], adjustedRow[x], weight)
                }
            }
            out.setPixels(outRow, 0, width, 0, y, width, 1)
        }
        if (scaled !== mask) scaled.recycle()
        return out
    }

    private fun scaleToMatch(mask: Bitmap, source: Bitmap): Bitmap =
        if (mask.width == source.width && mask.height == source.height) {
            mask
        } else {
            // Nearest neighbour: the mask is binary and must stay that way.
            Bitmap.createScaledBitmap(mask, source.width, source.height, false)
        }

    private fun lerpPixel(from: Int, to: Int, weight: Int): Int {
        var result = 0
        for (shift in CHANNEL_SHIFTS) {
            val a = (from ushr shift) and CHANNEL_MASK
            val b = (to ushr shift) and CHANNEL_MASK
            val blended = a + (b - a) * weight / ALPHA_MAX
            result = result or (blended shl shift)
        }
        return result
    }
}
