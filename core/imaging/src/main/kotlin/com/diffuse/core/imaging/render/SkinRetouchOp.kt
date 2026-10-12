package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/**
 * specs/skin_retouch_pipeline.md §6: inside the support the RGB comes from the stored result and
 * the alpha stays the input's; outside it nothing moves.
 *
 * The support is binary and used as a hard select. The result already has its feather and
 * strengths baked in, so there is no soft alpha to multiply — unlike [MaskBlend], which lerps.
 */
internal object SkinRetouchOp {

    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MASK = -0x1000000
    private const val RGB_MASK = 0x00FFFFFF

    /**
     * @param result ARGB_8888 at the working size; bilinear-scaled to [input] when they differ.
     * @param support ALPHA_8 at the working size; nearest-neighbour scaled so it stays binary.
     */
    fun apply(input: Bitmap, result: Bitmap, support: Bitmap): Bitmap {
        val width = input.width
        val height = input.height
        val scaledResult = scaled(result, width, height, filter = true)
        val scaledSupport = scaled(support, width, height, filter = false)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val inputRow = IntArray(width)
        val resultRow = IntArray(width)
        val supportRow = IntArray(width)
        for (y in 0 until height) {
            input.getPixels(inputRow, 0, width, 0, y, width, 1)
            scaledResult.getPixels(resultRow, 0, width, 0, y, width, 1)
            scaledSupport.getPixels(supportRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (supportRow[x] ushr ALPHA_SHIFT != 0) {
                    inputRow[x] = (inputRow[x] and ALPHA_MASK) or (resultRow[x] and RGB_MASK)
                }
            }
            out.setPixels(inputRow, 0, width, 0, y, width, 1)
        }
        if (scaledResult !== result) scaledResult.recycle()
        if (scaledSupport !== support) scaledSupport.recycle()
        return out
    }

    private fun scaled(bitmap: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap =
        if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, filter)
        }
}
