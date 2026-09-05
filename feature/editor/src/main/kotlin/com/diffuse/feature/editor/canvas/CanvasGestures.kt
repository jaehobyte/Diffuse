package com.diffuse.feature.editor.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Pinch/pan detection for the canvas.
 *
 * This exists instead of `detectTransformGestures` because that pairs badly with a
 * hoisted viewport: every pointer event would read the viewport as of the last
 * *composition*, so a burst of events within one frame all scale from the same stale
 * value and most of the gesture is lost. Seeding a working copy once per gesture and
 * accumulating locally keeps every event compounding correctly.
 */
internal suspend fun PointerInputScope.detectCanvasTransformGestures(
    viewportAtGestureStart: () -> CanvasViewport,
    onViewportChange: (CanvasViewport) -> Unit,
    computeNext: (CanvasViewport, Offset, Offset, Float) -> CanvasViewport,
) {
    awaitEachGesture {
        var working = viewportAtGestureStart()
        var pastSlop = false
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break

            val zoom = event.calculateZoom()
            val pan = event.calculatePan()

            if (!pastSlop) {
                accumulatedZoom *= zoom
                accumulatedPan += pan
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                if (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop) {
                    pastSlop = true
                }
            }

            if (pastSlop && (zoom != 1f || pan != Offset.Zero)) {
                val centroid = event.calculateCentroid(useCurrent = false)
                working = computeNext(working, centroid, pan, zoom)
                onViewportChange(working)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
