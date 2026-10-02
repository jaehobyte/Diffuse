package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.diffuse.core.imaging.model.Shot
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val HALF = 0.5f
private const val MAX_ALPHA = 255
private const val ASPECT_TOLERANCE = 0.01f
private const val ALPHA_SHIFT = 24
private const val CHANNEL = 0xFF
private val COLOUR_SHIFTS = intArrayOf(16, 8, 0)

/**
 * specs/multishot.md §4, §5. One shot drawn **Porter–Duff source-over** onto the canvas.
 *
 * Skia does the compositing on premultiplied pixels, which is what keeps it correct over a
 * transparent destination and keeps a soft edge from picking up a dark or light halo when the
 * subject is filtered while it is scaled or rotated. The subject's own alpha and [Shot.placement]'s
 * opacity are multiplied exactly once, by the paint.
 */
internal object MultiShotOp {

    /**
     * §4: the one transform — subject pixels to canvas pixels. [subject] may be decoded smaller
     * than the shot's stored size; the first scale undoes that, so a preview and an export put the
     * same point in the same place.
     */
    fun matrix(shot: Shot, subjectWidth: Int, subjectHeight: Int, canvasWidth: Int, canvasHeight: Int): Matrix {
        val placement = shot.placement
        val contain = containScale(shot.widthPx, shot.heightPx, canvasWidth, canvasHeight)
        return Matrix().apply {
            postScale(shot.widthPx / subjectWidth.toFloat(), shot.heightPx / subjectHeight.toFloat())
            postTranslate(-shot.widthPx * HALF, -shot.heightPx * HALF)
            postScale(contain * placement.scale, contain * placement.scale)
            postRotate(placement.rotationDeg)
            postTranslate(
                canvasWidth * (HALF + placement.offsetX),
                canvasHeight * (HALF + placement.offsetY),
            )
        }
    }

    /** §4: the initial fit — the whole photo inside the canvas, aspect kept. */
    fun containScale(photoWidth: Int, photoHeight: Int, canvasWidth: Int, canvasHeight: Int): Float =
        min(canvasWidth / photoWidth.toFloat(), canvasHeight / photoHeight.toFloat())

    /**
     * §6: whether a hero mask cut on one canvas fits another — the same canvas at another
     * resolution differs only by the rounding of its sides.
     */
    fun sameAspect(maskWidth: Int, maskHeight: Int, canvasWidth: Int, canvasHeight: Int): Boolean =
        abs(maskWidth.toFloat() / maskHeight - canvasWidth.toFloat() / canvasHeight) <=
            ASPECT_TOLERANCE * canvasWidth / canvasHeight

    /**
     * §6: the hero's protection — `lerp(composite, input, maskAlpha)` on **premultiplied** pixels,
     * so a transparent input contributes no colour and a soft edge never darkens. Mask 0 keeps the
     * composite pixel and 255 the input pixel exactly; the mask is scaled to the canvas bilinearly,
     * since it is soft. `MaskBlend` (selection, erase, fill) is not used: it interpolates straight
     * colour, which is only right where both sides are opaque.
     */
    fun protect(composite: Bitmap, input: Bitmap, mask: Bitmap): Bitmap {
        val width = composite.width
        val height = composite.height
        val scaled = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val compositeRow = IntArray(width)
        val inputRow = IntArray(width)
        val maskRow = IntArray(width)
        for (y in 0 until height) {
            composite.getPixels(compositeRow, 0, width, 0, y, width, 1)
            input.getPixels(inputRow, 0, width, 0, y, width, 1)
            scaled.getPixels(maskRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val weight = maskRow[x] ushr ALPHA_SHIFT
                compositeRow[x] = when (weight) {
                    0 -> compositeRow[x]
                    MAX_ALPHA -> inputRow[x]
                    else -> premultipliedLerp(compositeRow[x], inputRow[x], weight)
                }
            }
            out.setPixels(compositeRow, 0, width, 0, y, width, 1)
        }
        if (scaled !== mask) scaled.recycle()
        return out
    }

    /** [from] and [to] are straight ARGB (as `getPixels` gives them); the blend is premultiplied. */
    private fun premultipliedLerp(from: Int, to: Int, weight: Int): Int {
        val fromAlpha = from ushr ALPHA_SHIFT
        val toAlpha = to ushr ALPHA_SHIFT
        val alphaSum = fromAlpha * (MAX_ALPHA - weight) + toAlpha * weight
        if (alphaSum == 0) return 0
        var result = ((alphaSum + MAX_ALPHA / 2) / MAX_ALPHA) shl ALPHA_SHIFT
        for (shift in COLOUR_SHIFTS) {
            val a = (from ushr shift) and CHANNEL
            val b = (to ushr shift) and CHANNEL
            // Σ cᵢ·αᵢ·wᵢ / Σ αᵢ·wᵢ — the premultiplied sum, un-premultiplied by the blended alpha.
            val colour = (a * fromAlpha * (MAX_ALPHA - weight) + b * toAlpha * weight + alphaSum / 2) / alphaSum
            result = result or (colour.coerceIn(0, CHANNEL) shl shift)
        }
        return result
    }

    /**
     * Draws [subject] for [shot] onto [canvas], which must be mutable. Opacity 0 changes nothing,
     * and the canvas bounds clip whatever lands outside them.
     */
    fun draw(canvas: Bitmap, shot: Shot, subject: Bitmap) {
        val alpha = (shot.placement.opacity * MAX_ALPHA).roundToInt().coerceIn(0, MAX_ALPHA)
        if (alpha == 0) return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        Canvas(canvas).drawBitmap(
            subject,
            matrix(shot, subject.width, subject.height, canvas.width, canvas.height),
            paint,
        )
    }
}
