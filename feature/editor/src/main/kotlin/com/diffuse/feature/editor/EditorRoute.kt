package com.diffuse.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diffuse.feature.editor.canvas.cropOverlaySlot
import com.diffuse.feature.editor.tools.ToolSheetHost
import com.diffuse.feature.editor.tools.crop.CropSheet
import com.diffuse.feature.editor.tools.crop.STRAIGHTEN_MAX_DEG
import kotlinx.coroutines.launch

/**
 * specs/editor_shell.md. Crop is hosted here rather than in `ToolSheetHost` because it
 * carries its own state; the adjust tools share one signature.
 */
@Composable
fun EditorRoute(
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val document = state.document

    Box(modifier = modifier.fillMaxSize()) {
        EditorScreen(
            preview = state.preview,
            source = state.source,
            selectedTool = state.selectedTool,
            onToolClick = viewModel::onToolClick,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            canCompare = state.canCompare,
            canReset = state.canReset,
            onBack = {
                scope.launch {
                    viewModel.onLeave()
                    onBack()
                }
            },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onReset = viewModel::reset,
            onCompareChange = {},
            onExport = onExport,
            cropOverlay = if (state.selectedTool == Tool.Crop) {
                cropOverlaySlot(
                    rect = state.cropState.rect,
                    onRectChange = viewModel::onCropRectChange,
                    aspect = state.cropState.preset,
                )
            } else {
                null
            },
            sheet = document?.let { doc ->
                {
                    if (state.selectedTool == Tool.Crop) {
                        CropToolSheet(state = state, viewModel = viewModel)
                    } else {
                        ToolSheetHost(
                            selectedTool = state.selectedTool,
                            document = doc,
                            onValueChange = viewModel::onAdjust,
                            onValueChangeFinished = viewModel::onAdjustFinished,
                            onCancel = viewModel::cancelSheet,
                            onApply = viewModel::applySheet,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CropToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    CropSheet(
        preset = state.cropState.preset,
        straightenDeg = state.cropState.straightenDeg,
        onPresetChange = { preset ->
            viewModel.onCropChange(state.cropState.withPreset(preset, CANVAS_ASPECT))
        },
        onStraightenChange = { degrees ->
            viewModel.onCropChange(
                state.cropState.straightened(
                    degrees.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG),
                    CANVAS_ASPECT,
                ),
            )
        },
        onStraightenFinished = {},
        onRotate = { quarters -> viewModel.onCropChange(state.cropState.rotated(quarters)) },
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
    )
}

/**
 * The crop geometry needs the canvas aspect. Until the overlay reports its measured size
 * back, the fitted canvas of a 4:3 preview is the working assumption.
 */
private const val CANVAS_ASPECT = 4f / 3f
