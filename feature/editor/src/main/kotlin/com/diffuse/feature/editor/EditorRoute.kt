package com.diffuse.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.diffuse.feature.editor.canvas.CanvasGestureMode
import com.diffuse.feature.editor.canvas.CanvasPointTaps
import com.diffuse.feature.editor.canvas.OverlayTransform
import com.diffuse.feature.editor.canvas.cropOverlaySlot
import com.diffuse.feature.editor.canvas.selectionOverlaySlot
import com.diffuse.feature.editor.tools.MaskOption
import com.diffuse.feature.editor.tools.ToolSheetHost
import com.diffuse.feature.editor.tools.crop.CropSheet
import com.diffuse.feature.editor.tools.crop.STRAIGHTEN_MAX_DEG
import com.diffuse.feature.editor.tools.direct.DirectSheet
import com.diffuse.feature.editor.tools.fill.FillSheet
import com.diffuse.feature.editor.tools.prompt.VoicePromptBar
import com.diffuse.feature.editor.tools.select.Sam3SettingsSheet
import com.diffuse.feature.editor.tools.select.SelectSheet
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
            overlayTransform = cropTransform(state),
            disabledTools = disabledTools(state),
            gestureMode = if (state.selectedTool == Tool.Select) {
                CanvasGestureMode.SelectPoint
            } else {
                CanvasGestureMode.Pan
            },
            pointTaps = if (state.selectedTool != Tool.Select) {
                null
            } else {
                CanvasPointTaps(
                    onForeground = { viewModel.selection.addPoint(it.x, it.y, foreground = true) },
                    onBackground = { viewModel.selection.addPoint(it.x, it.y, foreground = false) },
                )
            },
            // specs/selection_tool.md §5: while `busy` the previous mask stays; only the
            // one-off `open` earns the overlay.
            busy = isBusy(state),
            busyLabelRes = busyLabel(state),
            onCancelWork = { cancelWork(viewModel) },
            message = message(state),
            onMessageShown = { clearMessages(viewModel) },
            canvasOverlay = canvasOverlay(state, viewModel),
            sheet = sheetFor(state, document, viewModel),
        )
    }
}

/** DESIGN.md §7: the overlay's cancel button reaches whichever tool is working. */
private fun cancelWork(viewModel: EditorViewModel) {
    viewModel.selection.cancelWork()
    viewModel.erase.cancel()
    viewModel.fill.cancel()
    viewModel.direct.cancelWork()
}

/** One snackbar, so the one that was shown is cleared wherever it came from. */
private fun clearMessages(viewModel: EditorViewModel) {
    viewModel.selection.onMessageShown()
    viewModel.erase.onMessageShown()
    viewModel.fill.onMessageShown()
    viewModel.direct.onMessageShown()
}

/** DESIGN.md §7: every AI call shows progress and a way out, so they share one flag. */
private fun isBusy(state: EditorUiState): Boolean =
    state.selection.working || state.erase.busy || state.fill.busy || state.direct.working

/** specs/selection_tool.md §1 and generative_erase.md §5: a tool that cannot work is greyed. */
private fun disabledTools(state: EditorUiState): Set<Tool> = buildSet {
    if (!state.selection.enabled) add(Tool.Select)
    if (!state.erase.enabled || state.document?.activeMaskId == null) add(Tool.Erase)
    // specs/generative_fill.md §6: the same two reasons, and the same greyed-but-tappable rule.
    if (!state.fill.enabled || state.document?.activeMaskId == null) add(Tool.Fill)
    // specs/vibe_edit.md §10: the key alone. A plan with no `Select` needs no SAM 3 server.
    if (!state.direct.enabled) add(Tool.Direct)
}

/** DESIGN.md §4 State display: the overlay says what is actually happening. */
private fun busyLabel(state: EditorUiState): Int = when {
    state.direct.planning -> R.string.direct_planning
    state.direct.running -> R.string.direct_running
    state.erase.busy -> R.string.erase_working
    state.fill.busy -> R.string.fill_working
    state.selection.phraseBusy -> R.string.select_prompt_working
    else -> R.string.select_preparing
}

/**
 * specs/vibe_edit.md §10: `direct_not_found` is the one line that names the word that failed,
 * so the direct tool's message carries its argument.
 */
@Composable
private fun message(state: EditorUiState): String? {
    val direct = state.direct.message
    return when {
        direct?.arg != null -> stringResource(direct.res, direct.arg)
        direct != null -> stringResource(direct.res)
        else -> (state.selection.message ?: state.erase.message ?: state.fill.message)
            ?.let { stringResource(it) }
    }
}

/** specs/canvas.md: one overlay slot, claimed by whichever tool is open. */
@Composable
private fun canvasOverlay(
    state: EditorUiState,
    viewModel: EditorViewModel,
): (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = when (state.selectedTool) {
    Tool.Crop -> cropOverlaySlot(
        rect = state.cropState.rect,
        onRectChange = { viewModel.onCropChange(state.cropState.copy(rect = it)) },
        aspect = state.cropState.preset,
    )
    Tool.Select -> selectionOverlaySlot(
        mask = state.selection.mask,
        points = state.selection.points,
        labels = state.selection.labels,
    )
    // specs/selection_tool.md §8.1: while a masked adjustment is being made, the scrim shows
    // where it will land. Toggle off and it disappears.
    null -> null
    else -> state.activeMask
        ?.takeIf { state.maskedAdjust }
        ?.let { selectionOverlaySlot(mask = it, points = emptyList(), labels = emptyList()) }
}

/**
 * The settings sheet wins over any tool sheet: it is the only way out of an unconfigured
 * provider (specs/segmentation.md §6).
 */
@Composable
private fun sheetFor(
    state: EditorUiState,
    document: com.diffuse.core.imaging.model.EditDocument?,
    viewModel: EditorViewModel,
): (@Composable () -> Unit)? {
    if (state.selection.showSettings) {
        return {
            Sam3SettingsSheet(
                config = state.selection.config,
                geminiApiKey = state.selection.geminiApiKey,
                onSave = viewModel.selection::saveSettings,
                onCancel = { viewModel.selection.setSettingsVisible(false) },
            )
        }
    }
    return document?.let { doc ->
        {
            when (state.selectedTool) {
                Tool.Crop -> CropToolSheet(state = state, viewModel = viewModel)
                Tool.Select -> SelectSheet(
                    state = state.selection,
                    onModeChange = viewModel.selection::setMode,
                    onInvert = viewModel.selection::invert,
                    onClear = viewModel.selection::clear,
                    onCutOut = viewModel::applyCutOut,
                    onCancel = viewModel::cancelSheet,
                    onApply = viewModel::applySheet,
                    promptBar = {
                        VoicePromptBar(
                            value = state.selection.phrase,
                            onValueChange = viewModel.selection::setPhrase,
                            onSubmit = viewModel.selection::submitPhrase,
                            speech = viewModel.speech,
                            enabled = !state.selection.phraseBusy,
                            onMessage = viewModel.selection::showMessage,
                        )
                    },
                )
                Tool.Fill -> FillToolSheet(state = state, viewModel = viewModel)
                Tool.Direct -> DirectToolSheet(state = state, viewModel = viewModel)
                else -> ToolSheetHost(
                    maskOption = MaskOption(
                        available = doc.activeMaskId != null,
                        maskedOnly = state.maskedAdjust,
                        onMaskedOnlyChange = viewModel::onMaskedAdjustChange,
                    ),
                    selectedTool = state.selectedTool,
                    document = doc,
                    onValueChange = viewModel::onAdjust,
                    onValueChangeFinished = viewModel::onAdjustFinished,
                    onCancel = viewModel::cancelSheet,
                    onApply = viewModel::applySheet,
                )
            }
        }
    }
}

/** tasks.md T24: Cancel closes the sheet, which removes the live rotation with it. */
private fun cropTransform(state: EditorUiState): OverlayTransform =
    if (state.selectedTool == Tool.Crop) {
        OverlayTransform(
            quarterTurns = state.cropState.quarterTurns,
            straightenDeg = state.cropState.straightenDeg,
        )
    } else {
        OverlayTransform.None
    }

/**
 * specs/generative_fill.md §6: the bar supplies the noun and the IME Done key does what 적용
 * does, so submitting from the keyboard commits rather than only dismissing it.
 */
@Composable
private fun FillToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    FillSheet(
        state = state.fill,
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
        promptBar = {
            VoicePromptBar(
                value = state.fill.prompt,
                onValueChange = viewModel.fill::setPrompt,
                onSubmit = { viewModel.applySheet() },
                speech = viewModel.speech,
                placeholder = stringResource(R.string.fill_placeholder),
                enabled = !state.fill.busy,
                onMessage = viewModel.fill::showMessage,
            )
        },
    )
}

/** specs/vibe_edit.md §3: the bar, the step list, and [취소 | 적용] with 적용 the one accent. */
@Composable
private fun DirectToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    DirectSheet(
        state = state.direct,
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
        promptBar = {
            VoicePromptBar(
                value = state.direct.request,
                onValueChange = viewModel.direct::setRequest,
                onSubmit = viewModel.direct::submit,
                speech = viewModel.speech,
                placeholder = stringResource(R.string.direct_placeholder),
                enabled = !state.direct.working,
                onMessage = viewModel.direct::showMessage,
            )
        },
    )
}

@Composable
private fun CropToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    CropSheet(
        preset = state.cropState.preset,
        straightenDeg = state.cropState.straightenDeg,
        onPresetChange = { preset ->
            viewModel.onCropChange(state.cropState.withPreset(preset))
        },
        onStraightenChange = { degrees ->
            viewModel.onCropChange(
                state.cropState.straightened(
                    degrees.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG),
                ),
            )
        },
        onStraightenFinished = {},
        onRotate = { quarters -> viewModel.onCropChange(state.cropState.rotated(quarters)) },
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
    )
}
