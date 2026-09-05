package com.diffuse.feature.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/** The two sizes every viewport calculation needs: the canvas, and the image in it. */
internal data class CanvasBounds(val canvas: Size, val image: Size)

/**
 * Viewport arithmetic for specs/canvas.md, kept out of the composable so it can be
 * asserted directly rather than through pixels.
 */
internal object CanvasMath {

    const val MIN_SCALE_FACTOR = 0.5f
    const val MAX_SCALE_FACTOR = 8f
    const val DOUBLE_TAP_FACTOR = 2f

    /** Pan clamp: at least this fraction of the image stays on screen, per axis. */
    const val MIN_VISIBLE_FRACTION = 0.25f

    private const val HALF = 0.5f

    fun fitScale(bounds: CanvasBounds, marginPx: Float): Float {
        val availableWidth = bounds.canvas.width - 2f * marginPx
        val availableHeight = bounds.canvas.height - 2f * marginPx
        val degenerate = bounds.canvas.isEmptyOrNegative() ||
            bounds.image.isEmptyOrNegative() ||
            availableWidth <= 0f ||
            availableHeight <= 0f
        return if (degenerate) {
            0f
        } else {
            min(availableWidth / bounds.image.width, availableHeight / bounds.image.height)
        }
    }

    fun clampScale(scale: Float, fitScale: Float): Float =
        if (fitScale <= 0f) scale
        else scale.coerceIn(fitScale * MIN_SCALE_FACTOR, fitScale * MAX_SCALE_FACTOR)

    /**
     * Derivation: with the image centred and displaced by `offset`, the on-screen overlap
     * along an axis is `canvas/2 - offset + extent/2`. Requiring that to stay at or above
     * `MIN_VISIBLE_FRACTION * extent` gives the bound below.
     */
    fun clampOffset(offset: Offset, bounds: CanvasBounds, scale: Float): Offset {
        val slack = HALF - MIN_VISIBLE_FRACTION
        val maxX = bounds.canvas.width * HALF + slack * bounds.image.width * scale
        val maxY = bounds.canvas.height * HALF + slack * bounds.image.height * scale
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    fun imageRect(bounds: CanvasBounds, viewport: CanvasViewport): Rect {
        val width = bounds.image.width * viewport.scale
        val height = bounds.image.height * viewport.scale
        val centerX = bounds.canvas.width * HALF + viewport.offset.x
        val centerY = bounds.canvas.height * HALF + viewport.offset.y
        return Rect(
            left = centerX - width * HALF,
            top = centerY - height * HALF,
            right = centerX + width * HALF,
            bottom = centerY + height * HALF,
        )
    }

    /** Pinch scales about the centroid; the image point under it stays put. */
    fun onTransform(
        viewport: CanvasViewport,
        bounds: CanvasBounds,
        centroid: Offset,
        pan: Offset,
        zoom: Float,
    ): CanvasViewport {
        if (viewport.scale <= 0f) return viewport
        val newScale = clampScale(viewport.scale * zoom, viewport.fitScale)
        val focus = centroid - bounds.canvas.center() - viewport.offset
        val newOffset = viewport.offset - focus * (newScale / viewport.scale - 1f) + pan
        return viewport.copy(
            scale = newScale,
            offset = clampOffset(newOffset, bounds, newScale),
        )
    }

    /** Fitted → 2× centred on the tap; anything else → back to fit. */
    fun onDoubleTap(
        viewport: CanvasViewport,
        bounds: CanvasBounds,
        tap: Offset,
    ): CanvasViewport = when {
        viewport.scale <= 0f || viewport.fitScale <= 0f -> viewport

        !viewport.isFitted -> viewport.copy(scale = viewport.fitScale, offset = Offset.Zero)

        else -> {
            val newScale = clampScale(viewport.fitScale * DOUBLE_TAP_FACTOR, viewport.fitScale)
            val focus = tap - bounds.canvas.center() - viewport.offset
            viewport.copy(
                scale = newScale,
                offset = clampOffset(-focus * (newScale / viewport.scale), bounds, newScale),
            )
        }
    }

    private fun Size.center() = Offset(width * HALF, height * HALF)

    private fun Size.isEmptyOrNegative() = width <= 0f || height <= 0f
}
