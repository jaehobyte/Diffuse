package com.diffuse.feature.editor.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Hoisted viewport state (specs/canvas.md). [scale] is absolute, not a multiple of
 * [fitScale]; both are kept so clamping and the fit/zoom toggle need no extra context.
 */
@Immutable
data class CanvasViewport(
    val scale: Float = 0f,
    val offset: Offset = Offset.Zero,
    val fitScale: Float = 0f,
) {
    val isFitted: Boolean get() = fitScale > 0f && scale == fitScale && offset == Offset.Zero
}

/**
 * Screen ↔ image-pixel mapping, published as [LocalCanvasTransform] so overlays such as
 * the crop tool (T15) map touches without repeating the math.
 */
@Immutable
data class CanvasTransform(
    val imageRect: Rect,
    val scale: Float,
) {
    fun screenToImage(point: Offset): Offset = Offset(
        x = (point.x - imageRect.left) / scale,
        y = (point.y - imageRect.top) / scale,
    )

    fun imageToScreen(point: Offset): Offset = Offset(
        x = imageRect.left + point.x * scale,
        y = imageRect.top + point.y * scale,
    )

    companion object {
        val Identity = CanvasTransform(Rect.Zero, 1f)
    }
}

val LocalCanvasTransform = compositionLocalOf { CanvasTransform.Identity }
