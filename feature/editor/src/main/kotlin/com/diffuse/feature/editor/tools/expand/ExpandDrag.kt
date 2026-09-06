package com.diffuse.feature.editor.tools.expand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.diffuse.core.imaging.model.MAX_MARGIN_FRACTION
import com.diffuse.core.imaging.model.Margins

/** Which edge of the expanded frame a drag grabbed. */
enum class ExpandEdge { Left, Top, Right, Bottom }

/**
 * Pure drag arithmetic, so specs/outpaint.md §6's two rules — outward only, clamped at
 * `MAX_MARGIN_FRACTION` — are asserted without gestures. The shape `CropDrag` already uses.
 */
object ExpandDrag {

    /**
     * The photo's rect inside the expanded [frame]: what a margin is a fraction *of*, and where
     * `OverlayTransform` draws the bitmap. One implementation, so the handles cannot end up
     * measuring against a different interior than the one on screen.
     */
    fun interiorOf(frame: Rect, margins: Margins): Rect {
        if (margins.isEmpty) return frame
        val width = frame.width / (1f + margins.left + margins.right)
        val height = frame.height / (1f + margins.top + margins.bottom)
        val left = frame.left + margins.left * width
        val top = frame.top + margins.top * height
        return Rect(left, top, left + width, top + height)
    }

    fun handleCenters(frame: Rect): Map<ExpandEdge, Offset> = mapOf(
        ExpandEdge.Left to Offset(frame.left, frame.center.y),
        ExpandEdge.Top to Offset(frame.center.x, frame.top),
        ExpandEdge.Right to Offset(frame.right, frame.center.y),
        ExpandEdge.Bottom to Offset(frame.center.x, frame.bottom),
    )

    /**
     * Returns null when the touch is away from every handle, so the canvas keeps the gesture
     * and pans — exactly as the crop overlay behaves (specs/canvas.md).
     */
    fun grabAt(point: Offset, frame: Rect, radiusPx: Float): ExpandEdge? =
        handleCenters(frame).entries
            .firstOrNull { (_, center) -> (point - center).getDistance() <= radiusPx }
            ?.key

    /**
     * §6: **outward only.** An inward drag clamps at 0 rather than shrinking the photo —
     * shrinking is 자르기's job and the two tools do not overlap. Dragging past
     * [MAX_MARGIN_FRACTION] stops there: no rubber-band, no snap.
     *
     * @param interior the photo's rect on screen, which is what the fraction is measured against.
     */
    fun apply(
        margins: Margins,
        edge: ExpandEdge,
        dragAmount: Offset,
        interior: Rect,
    ): Margins {
        if (interior.width <= 0f || interior.height <= 0f) return margins
        val dx = dragAmount.x / interior.width
        val dy = dragAmount.y / interior.height
        return when (edge) {
            ExpandEdge.Left -> margins.copy(left = clamp(margins.left - dx))
            ExpandEdge.Top -> margins.copy(top = clamp(margins.top - dy))
            ExpandEdge.Right -> margins.copy(right = clamp(margins.right + dx))
            ExpandEdge.Bottom -> margins.copy(bottom = clamp(margins.bottom + dy))
        }
    }

    private fun clamp(value: Float): Float = value.coerceIn(0f, MAX_MARGIN_FRACTION)
}
