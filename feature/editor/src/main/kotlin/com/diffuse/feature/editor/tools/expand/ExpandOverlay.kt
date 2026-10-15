package com.diffuse.feature.editor.tools.expand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
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
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.LocalCanvasTransform

/** specs/outpaint.md §6: four 24dp handles on the midpoint of each edge. */
private val HandleLength = 24.dp
private val HandleThickness = 3.dp

/**
 * DESIGN.md §2: `canvasOverlayInk` handles on a 1dp dark rim, the same pairing the crop guides
 * use, so they read over a white photo and the dark checkerboard alike.
 */
private val HandleShade = Color.Black.copy(alpha = 0.45f)
private val ShadeExtra = 1.dp

/** DESIGN.md §5: a 48dp touch target, so a 24dp radius around each handle's centre. */
private val HandleTouchRadius = 24.dp

/**
 * The area that will be generated, over the canvas's checkerboard: a translucent veil and a
 * 1dp rim, so the pending expansion reads as a region of the frame rather than as background.
 */
private const val PENDING_VEIL_ALPHA = 0.22f
private val FrameRim = 1.dp

const val ExpandOverlayTestTag = "ExpandOverlay"

/**
 * specs/outpaint.md §6, claiming canvas.md's single overlay slot the way 자르기 does.
 *
 * `OverlayTransform.margins` makes the canvas fit the expanded frame and draw the photo into its
 * interior, so the checkerboard `EditorCanvas` already paints behind every photo shows through
 * the margins — DESIGN.md §2's existing word for "no pixels here". Over it this draws the
 * pending area as a translucent veil with a rim, and the four handles.
 *
 * The gesture is keyed on nothing: every margin change refits the canvas and moves [frame], and
 * restarting the pointer input on that would end the drag after its first move. For the same
 * reason `detectCanvasTransformGestures` gives, the drag accumulates on a working copy seeded
 * at its start rather than on [margins] as of the last composition: a burst of moves within one
 * frame would otherwise all add to the same stale value, and most of the gesture would be lost.
 */
@Composable
fun ExpandOverlay(
    margins: Margins,
    onMarginsChange: (Margins) -> Unit,
    modifier: Modifier = Modifier,
) {
    val transform = LocalCanvasTransform.current
    val density = LocalDensity.current
    val radiusPx = with(density) { HandleTouchRadius.toPx() }
    val current by rememberUpdatedState(margins)
    val onChange by rememberUpdatedState(onMarginsChange)
    val frame by rememberUpdatedState(transform.imageRect)

    Canvas(
        modifier = modifier
            .testTag(ExpandOverlayTestTag)
            .fillMaxSize()
            .pointerInput(Unit) {
                var grab: ExpandEdge? = null
                var working = Margins.None
                detectDragGestures(
                    // A drag that starts away from a handle is left unconsumed, so the canvas
                    // keeps it and pans (canvas.md), exactly as the crop overlay behaves.
                    onDragStart = { start ->
                        grab = ExpandDrag.grabAt(start, frame, radiusPx)
                        working = current
                    },
                    onDragEnd = { grab = null },
                    onDragCancel = { grab = null },
                ) { change, dragAmount ->
                    val edge = grab ?: return@detectDragGestures
                    change.consume()
                    working = ExpandDrag.apply(
                        margins = working,
                        edge = edge,
                        dragAmount = dragAmount,
                        interior = ExpandDrag.interiorOf(frame, working),
                    )
                    onChange(working)
                }
            },
    ) {
        drawPendingArea(frame, ExpandDrag.interiorOf(frame, current))
        drawHandles(frame)
    }
}

/** The four strips between the photo and the expanded frame, veiled, and the frame's rim. */
private fun DrawScope.drawPendingArea(frame: Rect, interior: Rect) {
    if (frame.width <= 0f || frame.height <= 0f) return
    val veil = Tokens.canvasOverlayInk.copy(alpha = PENDING_VEIL_ALPHA)
    listOf(
        Rect(frame.left, frame.top, frame.right, interior.top),
        Rect(frame.left, interior.bottom, frame.right, frame.bottom),
        Rect(frame.left, interior.top, interior.left, interior.bottom),
        Rect(interior.right, interior.top, frame.right, interior.bottom),
    ).filter { it.width > 0f && it.height > 0f }.forEach { strip ->
        drawRect(color = veil, topLeft = strip.topLeft, size = strip.size)
    }
    drawRect(
        color = Tokens.canvasOverlayInk,
        topLeft = frame.topLeft,
        size = frame.size,
        style = Stroke(width = FrameRim.toPx()),
    )
}

private fun DrawScope.drawHandles(frame: Rect) {
    if (frame.width <= 0f || frame.height <= 0f) return
    val length = HandleLength.toPx()
    val thickness = HandleThickness.toPx()
    ExpandDrag.handleCenters(frame).forEach { (edge, center) ->
        val vertical = edge == ExpandEdge.Left || edge == ExpandEdge.Right
        val size = if (vertical) Size(thickness, length) else Size(length, thickness)
        val rim = ShadeExtra.toPx()
        drawRect(
            color = HandleShade,
            topLeft = Offset(center.x - size.width / 2f - rim, center.y - size.height / 2f - rim),
            size = Size(size.width + 2 * rim, size.height + 2 * rim),
        )
        drawRect(
            color = Tokens.canvasOverlayInk,
            topLeft = Offset(center.x - size.width / 2f, center.y - size.height / 2f),
            size = size,
        )
    }
}
