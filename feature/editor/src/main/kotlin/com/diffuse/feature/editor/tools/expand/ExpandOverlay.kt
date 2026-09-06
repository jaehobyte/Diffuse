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
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** DESIGN.md §5: a 48dp touch target, so a 24dp radius around each handle's centre. */
private val HandleTouchRadius = 24.dp

const val ExpandOverlayTestTag = "ExpandOverlay"

/**
 * specs/outpaint.md §6, claiming canvas.md's single overlay slot the way 자르기 does.
 *
 * The pending area itself is **not** drawn here: `OverlayTransform.margins` makes the canvas fit
 * the expanded frame and draw the photo into its interior, so the checkerboard `EditorCanvas`
 * already paints behind every photo shows through the margins on its own — DESIGN.md §2's
 * existing word for "no pixels here". What is left for the overlay is the four handles.
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
    val frame = transform.imageRect

    Canvas(
        modifier = modifier
            .testTag(ExpandOverlayTestTag)
            .fillMaxSize()
            .pointerInput(frame) {
                var grab: ExpandEdge? = null
                detectDragGestures(
                    // A drag that starts away from a handle is left unconsumed, so the canvas
                    // keeps it and pans (canvas.md), exactly as the crop overlay behaves.
                    onDragStart = { start -> grab = ExpandDrag.grabAt(start, frame, radiusPx) },
                    onDragEnd = { grab = null },
                    onDragCancel = { grab = null },
                ) { change, dragAmount ->
                    val edge = grab ?: return@detectDragGestures
                    change.consume()
                    onChange(
                        ExpandDrag.apply(
                            margins = current,
                            edge = edge,
                            dragAmount = dragAmount,
                            interior = ExpandDrag.interiorOf(frame, current),
                        ),
                    )
                }
            },
    ) {
        drawHandles(frame)
    }
}

private fun DrawScope.drawHandles(frame: Rect) {
    if (frame.width <= 0f || frame.height <= 0f) return
    val length = HandleLength.toPx()
    val thickness = HandleThickness.toPx()
    ExpandDrag.handleCenters(frame).forEach { (edge, center) ->
        val vertical = edge == ExpandEdge.Left || edge == ExpandEdge.Right
        val size = if (vertical) Size(thickness, length) else Size(length, thickness)
        drawRect(
            color = Tokens.editInk,
            topLeft = Offset(center.x - size.width / 2f, center.y - size.height / 2f),
            size = size,
        )
    }
}
