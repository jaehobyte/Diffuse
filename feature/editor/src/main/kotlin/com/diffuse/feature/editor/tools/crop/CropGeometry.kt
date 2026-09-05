package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** specs/crop.md: the preset chips. `Free` keeps whatever ratio the user drags. */
enum class AspectPreset(val ratio: Float?) {
    Free(null),
    Square(SQUARE_RATIO),
    FourFive(FOUR_FIVE_RATIO),
    NineSixteen(NINE_SIXTEEN_RATIO),
    SixteenNine(SIXTEEN_NINE_RATIO),
}

private const val SQUARE_RATIO = 1f
private const val FOUR_FIVE_RATIO = 0.8f
private const val NINE_SIXTEEN_RATIO = 0.5625f
private const val SIXTEEN_NINE_RATIO = 1.7777778f

/**
 * specs/crop.md geometry. All rects are normalised 0..1 against the canvas; the canvas
 * aspect is passed in because a normalised rect is only square on screen if it accounts
 * for it.
 */
object CropGeometry {

    /** specs/crop.md: "Min rect size: 10% of the short edge." */
    const val MIN_FRACTION = 0.1f

    private const val SHRINK_STEPS = 40

    /**
     * Shrinks [rect] about its centre, keeping its aspect, until every corner lies inside
     * the image rotated by [angleDeg] — specs/crop.md's "no empty corners, ever".
     *
     * Bisection rather than a closed form: the rect is not necessarily centred, so the
     * exact bound depends on which corner escapes first.
     */
    fun shrinkToFit(rect: RectF, angleDeg: Float, canvasAspect: Float): RectF {
        if (contains(rect, angleDeg, canvasAspect)) return RectF(rect)
        var low = 0f
        var high = 1f
        repeat(SHRINK_STEPS) {
            val mid = (low + high) * HALF
            if (contains(scaled(rect, mid), angleDeg, canvasAspect)) low = mid else high = mid
        }
        return scaled(rect, low)
    }

    /** Fits the largest centred rect of [preset] inside [bounds], keeping its centre. */
    fun applyPreset(rect: RectF, preset: AspectPreset, canvasAspect: Float): RectF {
        val ratio = preset.ratio ?: return RectF(rect)
        // A width:height ratio in screen space is ratio/canvasAspect in normalised space.
        val normalisedRatio = ratio / canvasAspect
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        var width = rect.width()
        var height = width / normalisedRatio
        if (height > 1f || height > rect.height() * MAX_GROWTH) {
            height = rect.height()
            width = height * normalisedRatio
        }
        width = width.coerceAtMost(1f)
        height = (width / normalisedRatio).coerceAtMost(1f)
        width = height * normalisedRatio
        return centered(centerX, centerY, width, height)
    }

    /** Every corner of [rect] must map back inside the unrotated canvas. */
    fun contains(rect: RectF, angleDeg: Float, canvasAspect: Float): Boolean {
        val radians = Math.toRadians(-angleDeg.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val corners = listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.right to rect.bottom,
            rect.left to rect.bottom,
        )
        return corners.all { (x, y) ->
            // Work in screen-proportional space so the rotation is not skewed.
            val dx = (x - HALF) * canvasAspect
            val dy = y - HALF
            val rotatedX = dx * cos - dy * sin
            val rotatedY = dx * sin + dy * cos
            abs(rotatedX) <= canvasAspect * HALF + EPSILON && abs(rotatedY) <= HALF + EPSILON
        }
    }

    fun clampToMinimum(rect: RectF): RectF = RectF(
        rect.left,
        rect.top,
        maxOf(rect.right, rect.left + MIN_FRACTION),
        maxOf(rect.bottom, rect.top + MIN_FRACTION),
    )

    private fun scaled(rect: RectF, factor: Float): RectF = centered(
        rect.centerX(),
        rect.centerY(),
        rect.width() * factor,
        rect.height() * factor,
    )

    private fun centered(centerX: Float, centerY: Float, width: Float, height: Float) = RectF(
        centerX - width * HALF,
        centerY - height * HALF,
        centerX + width * HALF,
        centerY + height * HALF,
    )

    private const val EPSILON = 1e-4f
    private const val HALF = 0.5f
    private const val MAX_GROWTH = 1f
}
