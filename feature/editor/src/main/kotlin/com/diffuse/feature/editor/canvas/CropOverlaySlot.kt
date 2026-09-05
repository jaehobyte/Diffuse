package com.diffuse.feature.editor.canvas

import android.graphics.RectF
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.diffuse.feature.editor.tools.crop.AspectPreset
import com.diffuse.feature.editor.tools.crop.CropOverlay

/**
 * Adapts the crop overlay to the canvas's overlay slot. It lives in `canvas` so
 * `EditorCanvas` keeps knowing nothing about the crop tool (specs/canvas.md).
 */
fun cropOverlaySlot(
    rect: RectF,
    onRectChange: (RectF) -> Unit,
    aspect: AspectPreset,
): @Composable BoxScope.() -> Unit = {
    CropOverlay(rect = rect, onRectChange = onRectChange, aspect = aspect)
}
