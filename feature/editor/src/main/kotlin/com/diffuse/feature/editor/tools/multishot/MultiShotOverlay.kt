package com.diffuse.feature.editor.tools.multishot

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.diffuse.core.imaging.render.CanvasPoint
import com.diffuse.core.ui.theme.AppColors
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.canvas.LocalCanvasTransform

const val MultiShotOverlayTestTag = "MultiShotOverlay"

/**
 * specs/multishot.md §2 requirement 7 and canvas.md's single overlay slot. **One finger** on the
 * photo moves the selected subject; the moment a second finger lands the gesture is left
 * unconsumed, so pinch-zoom and two-finger pan stay the canvas's (DESIGN.md §8). A touch that
 * starts outside the photo is never claimed.
 *
 * While 멀티샷 is open the canvas shows the un-cropped canonical canvas, so a drag divided by the
 * drawn photo's size already is the stored, zoom- and letterbox-free offset (§4).
 */
@Composable
fun MultiShotOverlay(
    onMove: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
    marker: CanvasPoint? = null,
    anchorEditing: Boolean = false,
    pathStart: CanvasPoint? = null,
    pathEnd: CanvasPoint? = null,
    slots: List<SlotMarker> = emptyList(),
    /** §4.3: draw the common baseline through [pathEnd] (the hero) and mark the hero's own slot. */
    baseline: Boolean = false,
) {
    val frame = LocalCanvasTransform.current.imageRect
    val move by rememberUpdatedState(onMove)
    val drag = stringResource(
        if (anchorEditing) R.string.multishot_anchor_description else R.string.multishot_drag_description,
    )
    val description = if (slots.isEmpty()) {
        drag
    } else {
        // §4.3: the even arrangement has no path, only the hero's baseline and its slots.
        val guide = if (baseline) R.string.multishot_baseline_description else R.string.multishot_slots_description
        drag + ". " + stringResource(guide, slots.size)
    }
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = Typography.label.copy(color = colors.ink)
    Box(
        modifier = modifier
            .testTag(MultiShotOverlayTestTag)
            .fillMaxSize()
            .semantics { contentDescription = description }
            .pointerInput(frame) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!frame.contains(down.position) || frame.isEmpty) return@awaitEachGesture
                    dragWhileSingle(frame) { delta -> move(delta.x, delta.y) }
                }
            }
            // §4.2 requirement 12: the path and its numbered slots — drawn over the canvas only, never
            // into the picture. Positions are canvas fractions, so zoom and pan are already in [frame].
            // §4.2 requirement 12: the path and its numbered slots — drawn over the canvas only, never
            // into the picture. Positions are canvas fractions, so zoom and pan are already in [frame].
            .drawBehind {
                if (baseline && pathEnd != null) drawBaseline(frame, pathEnd, colors)
                drawPath(frame, pathStart, pathEnd, slots, colors, measurer, labelStyle)
            }
            // §4.2 requirement 14: where the time layout holds the selected subject.
            .drawBehind {
                if (marker == null || frame.isEmpty) return@drawBehind
                val centre = Offset(frame.left + marker.x * frame.width, frame.top + marker.y * frame.height)
                // DESIGN.md: 적용 is the one accent, so the marker is ink and grows while it is the target.
                val radius = (if (anchorEditing) MARKER_EDITING_RADIUS else MARKER_RADIUS).toPx()
                drawCircle(color = colors.surface, radius = radius + MARKER_RING.toPx(), center = centre)
                drawCircle(color = colors.ink, radius = radius, center = centre)
            },
    )
}

@Suppress("LongParameterList") // The frame, the path's two ends, its slots and how to draw them.
private fun DrawScope.drawPath(
    frame: Rect,
    pathStart: CanvasPoint?,
    pathEnd: CanvasPoint?,
    slots: List<SlotMarker>,
    colors: AppColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    if (frame.isEmpty) return
    fun at(point: CanvasPoint) = Offset(frame.left + point.x * frame.width, frame.top + point.y * frame.height)
    if (pathStart != null && pathEnd != null) {
        drawLine(
            color = colors.surface,
            start = at(pathStart),
            end = at(pathEnd),
            strokeWidth = PATH_WIDTH.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
        )
    }
    slots.forEach { slot ->
        val centre = at(slot.point)
        val radius = SLOT_RADIUS.toPx()
        // Filled while the photo really sits on its slot; a ring once it was moved off it.
        drawCircle(color = colors.surface, radius = radius, center = centre)
        if (!slot.occupied) {
            drawCircle(color = colors.ink, radius = radius, center = centre, style = Stroke(MARKER_RING.toPx()))
        }
        val text = measurer.measure("${slot.number}", labelStyle)
        drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
    }
}

/** §4.3: the line every anchor sits on, across the canvas, and a square on the hero's own slot. */
private fun DrawScope.drawBaseline(frame: Rect, hero: CanvasPoint, colors: AppColors) {
    if (frame.isEmpty) return
    val y = frame.top + hero.y * frame.height
    drawLine(
        color = colors.surface,
        start = Offset(frame.left, y),
        end = Offset(frame.right, y),
        strokeWidth = PATH_WIDTH.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
    )
    val side = SLOT_RADIUS.toPx()
    val centre = Offset(frame.left + hero.x * frame.width, y)
    drawRect(
        color = colors.ink,
        topLeft = Offset(centre.x - side / 2, centre.y - side / 2),
        size = androidx.compose.ui.geometry.Size(side, side),
        style = Stroke(MARKER_RING.toPx()),
    )
}

private val MARKER_RADIUS = 6.dp
private val MARKER_EDITING_RADIUS = 10.dp
private val MARKER_RING = 2.dp
private val SLOT_RADIUS = 11.dp
private val PATH_WIDTH = 2.dp
private val DASH = 6.dp

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragWhileSingle(
    frame: Rect,
    onDelta: (Offset) -> Unit,
) {
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size != 1) return
        val change = pressed.single()
        val delta = change.positionChange()
        if (delta != Offset.Zero) {
            change.consume()
            onDelta(Offset(delta.x / frame.width, delta.y / frame.height))
        }
    }
}
