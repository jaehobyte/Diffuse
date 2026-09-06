package com.diffuse.core.ai.gemini

import android.graphics.Bitmap

/**
 * specs/generative_erase.md §4. `gemini-2.5-flash-image` has no mask parameter, so the mask has
 * to be expressed in the pixels themselves: every masked pixel becomes opaque white before the
 * image goes on the wire.
 *
 * White rather than mid-grey because it is out of gamut for almost every natural scene, so the
 * model cannot mistake it for content to preserve. There is no feathering and no partial blend:
 * the mask is binary (segmentation.md §1), and a soft edge would hand the model exactly the
 * ambiguity this is meant to remove.
 *
 * It lives here rather than in `core:imaging` because it is a detail of how one provider talks
 * to one model, not a rendering operation (ai_provider.md §2).
 */
internal object WhiteFill {

    private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    private const val ALPHA_SHIFT = 24

    /**
     * @param mask `ALPHA_8` at [image]'s size; a non-zero alpha is the region to erase.
     * @return a new `ARGB_8888` bitmap at [image]'s size. [image] is never mutated.
     */
    fun apply(image: Bitmap, mask: Bitmap): Bitmap {
        require(image.width == mask.width && image.height == mask.height) {
            "mask must be the image's size"
        }
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)

        // Runs once per erase on a bitmap of at most 1024px, so a plain loop is fast enough
        // and reads more simply than a SRC_IN composite.
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0) {
                    pixels[y * width + x] = OPAQUE_WHITE
                }
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
