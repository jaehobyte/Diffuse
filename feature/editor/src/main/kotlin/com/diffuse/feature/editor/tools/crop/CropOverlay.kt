package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.LocalCanvasTransform

/** specs/crop.md. */
private val HandleLength = 24.dp
private val HandleStroke = 3.dp
private val BorderStroke = 1.dp
private const val DIM_ALPHA = 0.5f
private const val THIRDS = 3

const val CropOverlayTestTag = "CropOverlay"

/**
 * specs/crop.md: dim outside the rect, 1dp `editInk` border, 24dp corner L-shapes and a
 * rule-of-thirds grid while dragging. Dragging inside moves the rect, a corner resizes it;
 * a drag that starts outside is left unconsumed so the canvas pans (canvas.md).
 */
@Composable
fun CropOverlay(
    rect: RectF,
    onRectChange: (RectF) -> Unit,
    modifier: Modifier = Modifier,
    aspect: AspectPreset = AspectPreset.Free,
) {
    val transform = LocalCanvasTransform.current
    val density = LocalDensity.current
    val handlePx = with(density) { HandleLength.toPx() }
    val current by rememberUpdatedState(rect)
    val onChange by rememberUpdatedState(onRectChange)
    val currentAspect by rememberUpdatedState(aspect)
    val imageRect = transform.imageRect

    Canvas(
        modifier = modifier
            .testTag(CropOverlayTestTag)
            .fillMaxSize()
            .pointerInput(imageRect) {
                var grab: CropGrab? = null
                detectDragGestures(
                    onDragStart = { start ->
                        grab = CropDrag.grabAt(start, current, imageRect, handlePx)
                    },
                    onDragEnd = { grab = null },
                    onDragCancel = { grab = null },
                ) { change, dragAmount ->
                    val handle = grab ?: return@detectDragGestures
                    change.consume()
                    onChange(
                        CropDrag.apply(
                            rect = current,
                            grab = handle,
                            dragAmount = dragAmount,
                            imageRect = imageRect,
                            aspect = currentAspect,
                        ),
                    )
                }
            },
    ) {
        drawCropOverlay(current, imageRect)
    }
}

private fun DrawScope.drawCropOverlay(rect: RectF, imageRect: Rect) {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return
    val cropRect = CropDrag.toScreen(rect, imageRect)

    // Dim everything outside the crop, clipped to the image.
    val dim = Color.Black.copy(alpha = DIM_ALPHA)
    drawRect(dim, imageRect.topLeft, Size(imageRect.width, cropRect.top - imageRect.top))
    drawRect(
        dim,
        Offset(imageRect.left, cropRect.bottom),
        Size(imageRect.width, imageRect.bottom - cropRect.bottom),
    )
    drawRect(
        dim,
        Offset(imageRect.left, cropRect.top),
        Size(cropRect.left - imageRect.left, cropRect.height),
    )
    drawRect(
        dim,
        Offset(cropRect.right, cropRect.top),
        Size(imageRect.right - cropRect.right, cropRect.height),
    )

    drawRect(
        color = Tokens.editInk,
        topLeft = cropRect.topLeft,
        size = cropRect.size,
        style = Stroke(width = BorderStroke.toPx()),
    )
    drawThirds(cropRect)
    drawCornerHandles(cropRect)
}

private fun DrawScope.drawThirds(cropRect: Rect) {
    val thin = Stroke(width = BorderStroke.toPx())
    for (step in 1 until THIRDS) {
        val x = cropRect.left + cropRect.width * step / THIRDS
        val y = cropRect.top + cropRect.height * step / THIRDS
        drawLine(
            Tokens.editInk.copy(alpha = 0.4f),
            Offset(x, cropRect.top),
            Offset(x, cropRect.bottom),
            thin.width,
        )
        drawLine(
            Tokens.editInk.copy(alpha = 0.4f),
            Offset(cropRect.left, y),
            Offset(cropRect.right, y),
            thin.width,
        )
    }
}

private fun DrawScope.drawCornerHandles(cropRect: Rect) {
    val length = HandleLength.toPx().coerceAtMost(cropRect.width / 2f)
    val width = HandleStroke.toPx()
    val corners = listOf(
        Triple(cropRect.left, cropRect.top, 1f to 1f),
        Triple(cropRect.right, cropRect.top, -1f to 1f),
        Triple(cropRect.left, cropRect.bottom, 1f to -1f),
        Triple(cropRect.right, cropRect.bottom, -1f to -1f),
    )
    corners.forEach { (x, y, direction) ->
        val (dx, dy) = direction
        drawLine(Tokens.editInk, Offset(x, y), Offset(x + dx * length, y), width)
        drawLine(Tokens.editInk, Offset(x, y), Offset(x, y + dy * length), width)
    }
}
