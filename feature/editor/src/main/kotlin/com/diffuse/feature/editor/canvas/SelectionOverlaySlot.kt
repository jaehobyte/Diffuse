package com.diffuse.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.diffuse.feature.editor.tools.select.SelectionOverlay

/**
 * Adapts the selection overlay to the canvas's overlay slot, so `EditorCanvas` keeps knowing
 * nothing about the select tool (specs/canvas.md), exactly as [cropOverlaySlot] does.
 */
fun selectionOverlaySlot(
    mask: Bitmap?,
    points: List<PointF>,
    labels: List<Boolean>,
): @Composable BoxScope.() -> Unit = {
    SelectionOverlay(mask = mask, points = points, labels = labels)
}
