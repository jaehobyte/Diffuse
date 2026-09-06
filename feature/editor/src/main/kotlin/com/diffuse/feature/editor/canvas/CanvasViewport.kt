package com.diffuse.feature.editor.canvas

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.diffuse.core.imaging.model.Margins
import com.diffuse.feature.editor.tools.expand.ExpandDrag

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
 * A live, canvas-level preview of a transform the document does not carry yet (tasks.md
 * T24): the crop tool's straighten slider and 90° buttons take effect immediately without
 * a `Renderer` pass. It mirrors `CropOp`, which turns first — changing the image's shape —
 * and then straightens inside the turned bounds.
 *
 * T65 added [margins] to the same idea rather than a second mechanism: specs/outpaint.md §6
 * wants the photo to shrink to leave room for the pending expansion, which is a change to the
 * size the canvas fits and to where the bitmap lands inside it — the two things this already
 * decides. The checkerboard `EditorCanvas` paints behind every photo then shows through the new
 * area unaided, which is DESIGN.md §2's existing word for "no pixels here".
 */
@Immutable
data class OverlayTransform(
    val quarterTurns: Int = 0,
    val straightenDeg: Float = 0f,
    /** specs/outpaint.md §3: fractions of the photo's own width and height, added per side. */
    val margins: Margins = Margins.None,
) {
    val angleDeg: Float get() = quarterTurns * QUARTER_TURN + straightenDeg

    /** An odd number of quarter turns swaps the image's width and height. */
    val swapsAxes: Boolean get() = quarterTurns % 2 != 0

    /** The size the turned, expanded image occupies: what the canvas fits and centres. */
    fun turnedSize(imageSize: Size): Size {
        val turned = if (swapsAxes) Size(imageSize.height, imageSize.width) else imageSize
        return Size(
            turned.width * (1f + margins.left + margins.right),
            turned.height * (1f + margins.top + margins.bottom),
        )
    }

    /**
     * Where the bitmap is drawn *before* [angleDeg] is applied about the centre: rotating
     * that rect by the quarter turns lands it exactly on [imageRect].
     */
    fun drawRect(imageRect: Rect): Rect {
        val interior = ExpandDrag.interiorOf(imageRect, margins)
        if (!swapsAxes) return interior
        val center = interior.center
        return Rect(
            left = center.x - interior.height / 2f,
            top = center.y - interior.width / 2f,
            right = center.x + interior.height / 2f,
            bottom = center.y + interior.width / 2f,
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

    /**
     * @return [point] as a 0..1 fraction of the photo, or null when it fell outside it.
     * specs/selection_tool.md §2 stores prompt points normalized, so they survive a zoom,
     * a re-render at a different resolution, and the upload's own downscale.
     */
    fun normalizedPoint(point: Offset): Offset? =
        if (imageRect.contains(point) && !imageRect.isEmpty) {
            Offset(
                (point.x - imageRect.left) / imageRect.width,
                (point.y - imageRect.top) / imageRect.height,
            )
        } else {
            null
        }

    companion object {
        val Identity = CanvasTransform(Rect.Zero, 1f)
    }
}

val LocalCanvasTransform = compositionLocalOf { CanvasTransform.Identity }
