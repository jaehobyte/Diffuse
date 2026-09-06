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

    /**
     * Grows [mask] by [radiusPx] in every direction. Binary in, binary out — this is a margin,
     * not a feather, so specs/generative_erase.md §4's "no partial blend" still holds.
     *
     * Two separable passes with a square kernel (Chebyshev distance), which is O(n·r) rather than
     * the O(n·r²) a circular kernel costs; at a margin of a dozen pixels the difference between a
     * square and a disc is not visible in an inpainting boundary.
     */
    fun dilated(mask: Bitmap, radiusPx: Int): Bitmap {
        require(radiusPx >= 0) { "radius must not be negative" }
        if (radiusPx == 0) return copyOf(mask)
        val width = mask.width
        val height = mask.height
        val source = BooleanArray(width * height) { isSet(mask, it % width, it / width) }
        val grown = dilateColumns(dilateRows(source, width, height, radiusPx), width, height, radiusPx)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out.setPixel(x, y, if (grown[y * width + x]) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        return out
    }

    private fun dilateRows(
        source: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ): BooleanArray {
        val out = BooleanArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                out[row + x] = (-radius..radius).any { offset ->
                    val moved = x + offset
                    moved in 0 until width && source[row + moved]
                }
            }
        }
        return out
    }

    private fun dilateColumns(
        source: BooleanArray,
        width: Int,
        height: Int,
        radius: Int,
    ): BooleanArray {
        val out = BooleanArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = (-radius..radius).any { offset ->
                    val moved = y + offset
                    moved in 0 until height && source[moved * width + x]
                }
            }
        }
        return out
    }

    /**
     * specs/selection_tool.md §4:
     * `Add -> max(acc, new)` and `Subtract -> min(acc, 255 - new)`.
     */
    fun merged(base: Bitmap?, incoming: Bitmap, mode: MergeMode): Bitmap {
        if (base == null) {
            // Subtracting from nothing leaves nothing; adding to nothing is the incoming mask.
            return if (mode == MergeMode.Add) copyOf(incoming) else empty(incoming)
        }
        require(base.width == incoming.width && base.height == incoming.height) {
            "masks must be the same size to merge"
        }
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ALPHA_8)
        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val had = isSet(base, x, y)
                val now = isSet(incoming, x, y)
                val set = if (mode == MergeMode.Add) had || now else had && !now
                out.setPixel(x, y, if (set) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        return out
    }

    /** Union of several instance masks, which is how a text prompt becomes one selection (§4). */
    fun union(masks: List<Bitmap>): Bitmap? {
        if (masks.isEmpty()) return null
        return masks.drop(1).fold(copyOf(masks.first())) { acc, next ->
            merged(acc, next, MergeMode.Add)
        }
    }

    /** Public since T67: `FillMask` walks a mask to find its bounding box. */
    fun isSet(mask: Bitmap, x: Int, y: Int) = (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0

    private fun copyOf(mask: Bitmap): Bitmap = mapPixels(mask) { it }

    private fun empty(like: Bitmap): Bitmap =
        Bitmap.createBitmap(like.width, like.height, Bitmap.Config.ALPHA_8)

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
