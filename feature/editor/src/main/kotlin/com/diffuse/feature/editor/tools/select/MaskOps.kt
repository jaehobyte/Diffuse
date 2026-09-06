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

    private fun isSet(mask: Bitmap, x: Int, y: Int) = (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0

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
