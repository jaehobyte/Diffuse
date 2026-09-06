package com.diffuse.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasGestureMode
import com.diffuse.feature.editor.canvas.CanvasPointTaps
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas
import com.diffuse.feature.editor.canvas.OverlayTransform

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
    /** tasks.md T22: enabled only while the document has operations to drop. */
    canReset: Boolean = false,
    onReset: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** DESIGN.md §4: sheets rise above the tool strip rather than replacing it. */
    sheet: (@Composable () -> Unit)? = null,
    /** specs/canvas.md: a tool draws inside the canvas, not over the whole screen. */
    canvasOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** tasks.md T24: the crop tool's rotation, previewed live without a re-render. */
    overlayTransform: OverlayTransform = OverlayTransform.None,
    /** specs/selection_tool.md §1: a tool with no working provider is greyed but still tappable. */
    disabledTools: Set<Tool> = emptySet(),
    /** specs/selection_tool.md §2: the select sheet claims the single finger while it is open. */
    gestureMode: CanvasGestureMode = CanvasGestureMode.Pan,
    pointTaps: CanvasPointTaps? = null,
    /** DESIGN.md §7: AI work always shows progress and a way out. */
    busy: Boolean = false,
    onCancelWork: () -> Unit = {},
    /** One-shot snackbar text; DESIGN.md §4 forbids toasts. */
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    // DESIGN.md §1: the editor is always warm-dark chrome, never the browse palette.
    AppTheme(mode = ThemeMode.Edit) {
        var viewport by rememberSaveable(stateSaver = ViewportSaver) {
            mutableStateOf(CanvasViewport())
        }
        // DESIGN.md §7: hold to compare with the original is the single comparison gesture.
        var comparing by remember { mutableStateOf(false) }
        var sheetHeightPx by remember { mutableIntStateOf(0) }
        val snackbarHost = remember { SnackbarHostState() }
        ShowMessage(message, snackbarHost, onMessageShown)
        val topBar: @Composable () -> Unit = {
            EditorTopBar(
                canUndo = canUndo,
                canRedo = canRedo,
                canReset = canReset,
                canCompare = canCompare,
                onBack = onBack,
                onUndo = onUndo,
                onRedo = onRedo,
                // tasks.md T22: dropping a Crop changes the dimensions, so refit the canvas.
                onReset = {
                    viewport = CanvasViewport()
                    onReset()
                },
                onCompareChange = {
                    comparing = it
                    onCompareChange(it)
                },
                onExport = onExport,
            )
        }
        var toolStripHeightPx by remember { mutableIntStateOf(0) }
        val sheetInset = canvasInset(sheet != null, sheetHeightPx, toolStripHeightPx)
        Box(modifier = modifier.testTag(EditorScreenTestTag).fillMaxSize()) {
            EditorBody(
                preview = preview,
                source = source,
                comparing = comparing,
                selectedTool = selectedTool,
                onToolClick = onToolClick,
                disabledTools = disabledTools,
                topBar = topBar,
                viewport = viewport,
                onViewportChange = { viewport = it },
                sheetInset = sheetInset,
                overlayTransform = overlayTransform,
                gestureMode = gestureMode,
                pointTaps = pointTaps,
                canvasOverlay = canvasOverlay,
                onToolStripHeight = { toolStripHeightPx = it },
            )
            if (busy) SelectionProgressOverlay(onCancel = onCancelWork)
            if (sheet != null) SheetOverlay(sheet) { sheetHeightPx = it }
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

/** specs/editor_shell.md: top bar 56dp / canvas / tool strip 72dp, in that order. */
@Composable
private fun EditorBody(
    preview: ImageBitmap?,
    source: ImageBitmap?,
    comparing: Boolean,
    selectedTool: Tool?,
    onToolClick: (Tool) -> Unit,
    disabledTools: Set<Tool>,
    topBar: @Composable () -> Unit,
    viewport: CanvasViewport,
    onViewportChange: (CanvasViewport) -> Unit,
    sheetInset: Dp,
    overlayTransform: OverlayTransform,
    gestureMode: CanvasGestureMode,
    pointTaps: CanvasPointTaps?,
    canvasOverlay: (@Composable BoxScope.() -> Unit)?,
    onToolStripHeight: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
        topBar()
        EditorCanvas(
            bitmap = if (comparing) source ?: preview else preview,
            viewport = viewport,
            onViewportChange = onViewportChange,
            modifier = Modifier.weight(1f).padding(bottom = sheetInset),
            contentDescription = stringResource(
                if (comparing) R.string.editor_canvas_source else R.string.editor_canvas_edited,
            ),
            overlayTransform = overlayTransform,
            gestureMode = gestureMode,
            pointTaps = pointTaps,
            overlay = canvasOverlay,
        )
        EditorToolStrip(
            selectedTool = selectedTool,
            onToolClick = onToolClick,
            disabledTools = disabledTools,
            modifier = Modifier.navigationBarsPadding().onSizeChanged { onToolStripHeight(it.height) },
        )
    }
}

/** DESIGN.md §4 State display: errors are a snackbar on a dark surface, never a toast. */
@Composable
private fun ShowMessage(
    message: String?,
    host: SnackbarHostState,
    onMessageShown: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onMessageShown()
        }
    }
}

/** DESIGN.md §4: the sheet rises above the tool strip rather than pushing it off screen. */
@Composable
private fun BoxScope.SheetOverlay(sheet: @Composable () -> Unit, onHeight: (Int) -> Unit) {
    Box(
        modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { onHeight(it.height) },
    ) { sheet() }
}

/**
 * A sheet floats over the tool strip and eats into the canvas below it. Handing the canvas
 * that overlap as an inset is what makes it refit into the space that is left, instead of
 * leaving the photo behind the sheet for the user to pinch out.
 */
@Composable
private fun canvasInset(sheetOpen: Boolean, sheetHeightPx: Int, toolStripHeightPx: Int): Dp =
    with(LocalDensity.current) {
        if (!sheetOpen) 0.dp else (sheetHeightPx - toolStripHeightPx).coerceAtLeast(0).toDp()
    }

/** specs/editor_shell.md edge case: the viewport survives a configuration change. */
private val ViewportSaver = listSaver<CanvasViewport, Float>(
    save = { listOf(it.scale, it.offset.x, it.offset.y, it.fitScale) },
    restore = { CanvasViewport(it[0], Offset(it[1], it[2]), it[3]) },
)
