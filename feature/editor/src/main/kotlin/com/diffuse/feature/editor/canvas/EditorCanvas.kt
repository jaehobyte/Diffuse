package com.diffuse.feature.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.Tokens
import kotlin.math.ceil
import kotlin.math.roundToInt

/** DESIGN.md §5: at least 16dp of editBackground around the photo. */
private val CanvasMargin = 16.dp

/** DESIGN.md §2: transparency checkerboard, 8dp cells. */
private val CheckerCell = 8.dp

private const val SHARP_PIXEL_ZOOM = 2f

const val EditorCanvasTestTag = "EditorCanvas"

/**
 * Displays the preview bitmap and owns viewport gestures (specs/canvas.md).
 * It never mutates the document.
 */
@Composable
fun EditorCanvas(
    bitmap: ImageBitmap?,
    viewport: CanvasViewport,
    onViewportChange: (CanvasViewport) -> Unit,
    modifier: Modifier = Modifier,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val marginPx = with(density) { CanvasMargin.toPx() }
    val checkerPx = with(density) { CheckerCell.toPx() }

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val imageSize = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    val bounds = CanvasBounds(canvasSize, imageSize)
    val fitScale = CanvasMath.fitScale(bounds, marginPx)

    RefitOnSizeChange(fitScale, viewport, onViewportChange)

    val transform = CanvasTransform(
        imageRect = CanvasMath.imageRect(bounds, viewport),
        scale = viewport.scale.takeIf { it > 0f } ?: 1f,
    )

    Box(
        modifier = modifier
            .testTag(EditorCanvasTestTag)
            .fillMaxSize()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .canvasGestures(bounds, viewport, onViewportChange),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Tokens.editBackground, size = size)
            if (bitmap != null && viewport.scale > 0f) {
                drawPhoto(bitmap, transform.imageRect, viewport, checkerPx)
            }
        }
        if (overlay != null) {
            CompositionLocalProvider(LocalCanvasTransform provides transform) {
                overlay()
            }
        }
    }
}

/**
 * specs/canvas.md: refit when the canvas or bitmap changes, but keep the user's zoom
 * if they had one.
 */
@Composable
private fun RefitOnSizeChange(
    fitScale: Float,
    viewport: CanvasViewport,
    onViewportChange: (CanvasViewport) -> Unit,
) {
    LaunchedEffect(fitScale, viewport.fitScale, viewport.isFitted) {
        if (fitScale <= 0f || fitScale == viewport.fitScale) return@LaunchedEffect
        onViewportChange(
            if (viewport.fitScale <= 0f || viewport.isFitted) {
                CanvasViewport(scale = fitScale, offset = Offset.Zero, fitScale = fitScale)
            } else {
                viewport.copy(
                    fitScale = fitScale,
                    scale = CanvasMath.clampScale(viewport.scale, fitScale),
                )
            },
        )
    }
}

@Composable
private fun Modifier.canvasGestures(
    bounds: CanvasBounds,
    viewport: CanvasViewport,
    onViewportChange: (CanvasViewport) -> Unit,
): Modifier {
    val current by rememberUpdatedState(viewport)
    val currentBounds by rememberUpdatedState(bounds)
    val onChange by rememberUpdatedState(onViewportChange)
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { tap ->
                    onChange(CanvasMath.onDoubleTap(current, currentBounds, tap))
                },
            )
        }
        .pointerInput(Unit) {
            detectCanvasTransformGestures(
                viewportAtGestureStart = { current },
                onViewportChange = { onChange(it) },
                computeNext = { viewportNow, centroid, pan, zoom ->
                    CanvasMath.onTransform(viewportNow, currentBounds, centroid, pan, zoom)
                },
            )
        }
}

private fun DrawScope.drawPhoto(
    bitmap: ImageBitmap,
    rect: Rect,
    viewport: CanvasViewport,
    checkerPx: Float,
) {
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawCheckerboard(rect, checkerPx)
    }
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
        filterQuality = if (viewport.scale < viewport.fitScale * SHARP_PIXEL_ZOOM) {
            FilterQuality.High
        } else {
            FilterQuality.None
        },
    )
}

private fun DrawScope.drawCheckerboard(rect: Rect, cellPx: Float) {
    if (cellPx <= 0f) return
    drawRect(color = Tokens.canvasCheckerA, topLeft = rect.topLeft, size = rect.size)
    val columns = ceil(rect.width / cellPx).toInt()
    val rows = ceil(rect.height / cellPx).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 == 0) continue
            drawRect(
                color = Tokens.canvasCheckerB,
                topLeft = Offset(rect.left + column * cellPx, rect.top + row * cellPx),
                size = Size(cellPx, cellPx),
            )
        }
    }
}
