package com.diffuse.core.imaging.model

import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * specs/outpaint.md §3. Past half a dimension per side the model is inventing more picture than
 * it was given, and the answer stops resembling the photograph.
 */
const val MAX_MARGIN_FRACTION = 0.5f

/**
 * specs/outpaint.md §3. Fractions of the source's width and height added on each side; each in
 * `0f..MAX_MARGIN_FRACTION`.
 *
 * A fraction rather than a pixel count, because the same margins have to mean the same thing at
 * preview resolution and at export resolution — which is the whole reason `Outpaint` can keep the
 * source's own pixels in the interior (§4).
 */
data class Margins(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {

    val isEmpty: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    /** Every margin inside `0f..MAX_MARGIN_FRACTION`; the tool clamps too, this is the backstop. */
    fun clamped(): Margins = Margins(
        left = left.clamp(),
        top = top.clamp(),
        right = right.clamp(),
        bottom = bottom.clamp(),
    )

    fun padLeft(width: Int): Int = (left * width).roundToInt()

    fun padTop(height: Int): Int = (top * height).roundToInt()

    /**
     * The expanded size is the sum of its parts rather than `(1 + left + right) * width` rounded,
     * so the interior rect always fits exactly and no row of the source falls off the edge.
     */
    fun expandedWidth(width: Int): Int = padLeft(width) + width + (right * width).roundToInt()

    fun expandedHeight(height: Int): Int = padTop(height) + height + (bottom * height).roundToInt()

    private fun Float.clamp(): Float = coerceIn(0f, MAX_MARGIN_FRACTION)

    companion object {
        val None = Margins()

        /**
         * specs/outpaint.md §3: a `Crop.rect` is normalised against the canvas it was made on,
         * so committing or replacing an `Outpaint` moves it — pure arithmetic, no pixels.
         *
         * [from] is the margins the rect was measured against ([None] for a document that had no
         * outpaint yet) and [to] the ones it is moving to, so a second 확대 re-bases from the
         * source rather than compounding. `angleDeg` is unaffected.
         */
        fun renormalize(rect: RectF, from: Margins, to: Margins): RectF {
            val fromWidth = 1f + from.left + from.right
            val fromHeight = 1f + from.top + from.bottom
            val toWidth = 1f + to.left + to.right
            val toHeight = 1f + to.top + to.bottom
            return RectF(
                (rect.left * fromWidth - from.left + to.left) / toWidth,
                (rect.top * fromHeight - from.top + to.top) / toHeight,
                (rect.right * fromWidth - from.left + to.left) / toWidth,
                (rect.bottom * fromHeight - from.top + to.top) / toHeight,
            )
        }
    }
}
