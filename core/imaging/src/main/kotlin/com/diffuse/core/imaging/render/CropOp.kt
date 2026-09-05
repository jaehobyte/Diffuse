package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.diffuse.core.imaging.model.Operation
import kotlin.math.roundToInt

/**
 * specs/render.md: rotate about the centre, then crop `rect`.
 *
 * `angleDeg` carries both the 90° steps and the straighten (specs/crop.md), so it is split
 * back apart here: the quarter turns change the canvas dimensions, the straighten rotates
 * the image *under* a fixed rect, and `rect` is normalised against the post-quarter-turn
 * canvas. The crop tool guarantees the rect stays inside the rotated image, so no black
 * corner can appear.
 */
internal object CropOp {

    private const val QUARTER_TURN = 90f
    private const val FULL_TURN = 360f
    private const val TURNS_PER_CIRCLE = 4

    fun apply(bitmap: Bitmap, operation: Operation.Crop): Bitmap {
        val turned = applyQuarterTurns(bitmap, quarterTurnsOf(operation.angleDeg))
        val straightened = applyStraighten(turned, straightenOf(operation.angleDeg))
        return cropTo(straightened, operation)
    }

    /** Quarter turns are the part of the angle that changes the canvas shape. */
    fun quarterTurnsOf(angleDeg: Float): Int {
        val normalised = ((angleDeg % FULL_TURN) + FULL_TURN) % FULL_TURN
        return ((normalised / QUARTER_TURN).roundToInt()) % TURNS_PER_CIRCLE
    }

    /** What is left after the quarter turns: the straighten, always within ±45°. */
    fun straightenOf(angleDeg: Float): Float = angleDeg - quarterTurnsOf(angleDeg) * QUARTER_TURN

    private fun applyQuarterTurns(bitmap: Bitmap, turns: Int): Bitmap {
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turns * QUARTER_TURN) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyStraighten(bitmap: Bitmap, angleDeg: Float): Bitmap {
        if (angleDeg == 0f) return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            rotate(angleDeg, bitmap.width / 2f, bitmap.height / 2f)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return output
    }

    private fun cropTo(bitmap: Bitmap, operation: Operation.Crop): Bitmap {
        val rect = operation.rect
        val left = (rect.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (rect.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (rect.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (rect.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}
