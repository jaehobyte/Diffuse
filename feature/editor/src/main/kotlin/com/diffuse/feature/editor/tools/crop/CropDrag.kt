package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Which part of the crop rect a drag grabbed. */
enum class CropGrab { TopLeft, TopRight, BottomLeft, BottomRight, Inside }

/** Pure drag arithmetic, so specs/crop.md's clamping rules are asserted without gestures. */
object CropDrag {

    fun toScreen(rect: RectF, imageRect: Rect): Rect = Rect(
        left = imageRect.left + rect.left * imageRect.width,
        top = imageRect.top + rect.top * imageRect.height,
        right = imageRect.left + rect.right * imageRect.width,
        bottom = imageRect.top + rect.bottom * imageRect.height,
    )

    /**
     * Returns null when the touch is outside the rect and away from its handles, so the
     * canvas keeps the gesture and pans (specs/crop.md).
     */
    fun grabAt(point: Offset, rect: RectF, imageRect: Rect, handlePx: Float): CropGrab? {
        val screen = toScreen(rect, imageRect)
        val corners = mapOf(
            CropGrab.TopLeft to Offset(screen.left, screen.top),
            CropGrab.TopRight to Offset(screen.right, screen.top),
            CropGrab.BottomLeft to Offset(screen.left, screen.bottom),
            CropGrab.BottomRight to Offset(screen.right, screen.bottom),
        )
        corners.forEach { (grab, corner) ->
            if ((point - corner).getDistance() <= handlePx) return grab
        }
        return if (screen.contains(point)) CropGrab.Inside else null
    }

    fun apply(
        rect: RectF,
        grab: CropGrab,
        dragAmount: Offset,
        imageRect: Rect,
        aspect: AspectPreset,
    ): RectF {
        if (imageRect.width <= 0f || imageRect.height <= 0f) return rect
        val dx = dragAmount.x / imageRect.width
        val dy = dragAmount.y / imageRect.height
        val moved = when (grab) {
            CropGrab.Inside -> translate(rect, dx, dy)
            CropGrab.TopLeft -> RectF(rect.left + dx, rect.top + dy, rect.right, rect.bottom)
            CropGrab.TopRight -> RectF(rect.left, rect.top + dy, rect.right + dx, rect.bottom)
            CropGrab.BottomLeft -> RectF(rect.left + dx, rect.top, rect.right, rect.bottom + dy)
            CropGrab.BottomRight -> RectF(rect.left, rect.top, rect.right + dx, rect.bottom + dy)
        }
        val sized = if (grab == CropGrab.Inside) moved else enforceMinimum(moved, grab)
        val aspected = if (grab == CropGrab.Inside || aspect.ratio == null) {
            sized
        } else {
            CropGeometry.applyPreset(sized, aspect, imageRect.width / imageRect.height)
        }
        return clampInsideImage(aspected)
    }

    private fun translate(rect: RectF, dx: Float, dy: Float): RectF {
        val clampedDx = dx.coerceIn(-rect.left, 1f - rect.right)
        val clampedDy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return RectF(
            rect.left + clampedDx,
            rect.top + clampedDy,
            rect.right + clampedDx,
            rect.bottom + clampedDy,
        )
    }

    /** specs/crop.md: dragging past the minimum clamps rather than inverting the rect. */
    private fun enforceMinimum(rect: RectF, grab: CropGrab): RectF {
        val min = CropGeometry.MIN_FRACTION
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        when (grab) {
            CropGrab.TopLeft -> {
                left = left.coerceAtMost(right - min)
                top = top.coerceAtMost(bottom - min)
            }
            CropGrab.TopRight -> {
                right = right.coerceAtLeast(left + min)
                top = top.coerceAtMost(bottom - min)
            }
            CropGrab.BottomLeft -> {
                left = left.coerceAtMost(right - min)
                bottom = bottom.coerceAtLeast(top + min)
            }
            CropGrab.BottomRight -> {
                right = right.coerceAtLeast(left + min)
                bottom = bottom.coerceAtLeast(top + min)
            }
            CropGrab.Inside -> Unit
        }
        return RectF(left, top, right, bottom)
    }

    private fun clampInsideImage(rect: RectF) = RectF(
        rect.left.coerceIn(0f, 1f),
        rect.top.coerceIn(0f, 1f),
        rect.right.coerceIn(0f, 1f),
        rect.bottom.coerceIn(0f, 1f),
    )
}
