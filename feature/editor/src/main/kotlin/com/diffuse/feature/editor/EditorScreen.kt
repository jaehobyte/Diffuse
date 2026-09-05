package com.diffuse.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas

const val EditorScreenTestTag = "EditorScreen"

/** specs/editor_shell.md: top bar 56dp / canvas / tool strip 72dp, portrait only in v1. */
@Composable
fun EditorScreen(
    preview: ImageBitmap?,
    selectedTool: Tool?,
    /** Shown instead of [preview] while the compare button is held (specs/editor_shell.md). */
    source: ImageBitmap? = null,
    onToolClick: (Tool) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canCompare: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    /** DESIGN.md §4: sheets rise above the tool strip rather than replacing it. */
    sheet: (@Composable () -> Unit)? = null,
    /** specs/canvas.md: the crop tool draws inside the canvas, not over the whole screen. */
    cropOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    // DESIGN.md §1: the editor is always warm-dark chrome, never the browse palette.
    AppTheme(mode = ThemeMode.Edit) {
        var viewport by rememberSaveable(stateSaver = ViewportSaver) {
            mutableStateOf(CanvasViewport())
        }
        // DESIGN.md §7: hold to compare with the original is the single comparison gesture.
        var comparing by remember { mutableStateOf(false) }
        Box(modifier = modifier.testTag(EditorScreenTestTag).fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Tokens.editBackground),
        ) {
            EditorTopBar(
                canUndo = canUndo,
                canRedo = canRedo,
                canCompare = canCompare,
                onBack = onBack,
                onUndo = onUndo,
                onRedo = onRedo,
                onCompareChange = {
                    comparing = it
                    onCompareChange(it)
                },
                onExport = onExport,
            )
            EditorCanvas(
                bitmap = if (comparing) source ?: preview else preview,
                viewport = viewport,
                onViewportChange = { viewport = it },
                modifier = Modifier.weight(1f),
                contentDescription = stringResource(
                    if (comparing) R.string.editor_canvas_source else R.string.editor_canvas_edited,
                ),
                overlay = cropOverlay,
            )
            EditorToolStrip(
                selectedTool = selectedTool,
                onToolClick = onToolClick,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
        if (sheet != null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) { sheet() }
        }
        }
    }
}

/** specs/editor_shell.md edge case: the viewport survives a configuration change. */
private val ViewportSaver = listSaver<CanvasViewport, Float>(
    save = { listOf(it.scale, it.offset.x, it.offset.y, it.fitScale) },
    restore = { CanvasViewport(it[0], Offset(it[1], it[2]), it[3]) },
)
