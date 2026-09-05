package com.diffuse.feature.editor.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

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
 * A live, canvas-level preview of a rotation the document does not carry yet (tasks.md
 * T24): the crop tool's straighten slider and 90° buttons take effect immediately without
 * a `Renderer` pass. It mirrors `CropOp`, which turns first — changing the image's shape —
 * and then straightens inside the turned bounds.
 */
@Immutable
data class OverlayTransform(
    val quarterTurns: Int = 0,
    val straightenDeg: Float = 0f,
) {
    val angleDeg: Float get() = quarterTurns * QUARTER_TURN + straightenDeg

    /** An odd number of quarter turns swaps the image's width and height. */
    val swapsAxes: Boolean get() = quarterTurns % 2 != 0

    /** The size the turned image occupies, which is what the canvas fits and centres. */
    fun turnedSize(imageSize: Size): Size =
        if (swapsAxes) Size(imageSize.height, imageSize.width) else imageSize

    /**
     * Where the bitmap is drawn *before* [angleDeg] is applied about the centre: rotating
     * that rect by the quarter turns lands it exactly on [imageRect].
     */
    fun drawRect(imageRect: Rect): Rect = if (!swapsAxes) {
        imageRect
    } else {
        val center = imageRect.center
        Rect(
            left = center.x - imageRect.height / 2f,
            top = center.y - imageRect.width / 2f,
            right = center.x + imageRect.height / 2f,
            bottom = center.y + imageRect.width / 2f,
        )
    }

    companion object {
        private const val QUARTER_TURN = 90f
        val None = OverlayTransform()
    }
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
