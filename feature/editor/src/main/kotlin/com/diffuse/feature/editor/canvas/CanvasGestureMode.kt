package com.diffuse.feature.editor.canvas

import androidx.compose.ui.geometry.Offset

/**
 * specs/canvas.md kept this field from v1 with only one value, so v2 could add a tool that
 * claims the single finger without touching the viewport gestures (architecture.md §6).
 */
enum class CanvasGestureMode {
    /** One finger pans. */
    Pan,

    /** specs/selection_tool.md §2: tap adds a foreground point, long-press a background one. */
    SelectPoint,
}

/** Taps reported as a 0..1 fraction of the photo; a tap that missed it is never delivered. */
data class CanvasPointTaps(
    val onForeground: (Offset) -> Unit,
    val onBackground: (Offset) -> Unit,
)
