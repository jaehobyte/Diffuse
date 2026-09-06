package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.Rect
import com.diffuse.core.ai.Margins
import kotlin.math.roundToInt

/**
 * specs/outpaint.md §5. The mask trick of generative_erase.md §4, generalized: `WhiteFill` paints
 * a region white, this paints a **border** white. Same reason in both — `gemini-2.5-flash-image`
 * has no mask parameter, so "invent something here" has to be said in the pixels.
 *
 * It lives beside `WhiteFill` rather than in `core:imaging` for the reason §4 gives: it is a
 * detail of how one provider talks to one model, not a rendering operation.
 */
internal object WhitePad {

    private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()

    /**
     * The interior rect of the canvas [margins] would produce around an [width] × [height] image.
     *
     * The expanded size is the sum of its parts rather than `(1 + left + right) * width` rounded,
     * matching `core:imaging`'s own arithmetic: the interior then always fits exactly, and no row
     * of the photograph falls off the edge of the canvas it is copied into.
     */
    fun interiorOf(width: Int, height: Int, margins: Margins): Rect {
        val left = (margins.left * width).roundToInt()
        val top = (margins.top * height).roundToInt()
        return Rect(left, top, left + width, top + height)
    }

    fun paddedWidth(width: Int, margins: Margins): Int =
        (margins.left * width).roundToInt() + width + (margins.right * width).roundToInt()

    fun paddedHeight(height: Int, margins: Margins): Int =
        (margins.top * height).roundToInt() + height + (margins.bottom * height).roundToInt()

    /**
     * @return a new `ARGB_8888` canvas [margins] larger than [image], the new area opaque
     * `#FFFFFF` and the interior copied verbatim. [image] is never mutated.
     */
    fun apply(image: Bitmap, margins: Margins): Bitmap {
        require(margins.left >= 0f && margins.top >= 0f) { "margins must not be negative" }
        require(margins.right >= 0f && margins.bottom >= 0f) { "margins must not be negative" }

        val width = paddedWidth(image.width, margins)
        val height = paddedHeight(image.height, margins)
        val interior = interiorOf(image.width, image.height, margins)

        val pixels = IntArray(width * height) { OPAQUE_WHITE }
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            row.copyInto(pixels, (interior.top + y) * width + interior.left)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
