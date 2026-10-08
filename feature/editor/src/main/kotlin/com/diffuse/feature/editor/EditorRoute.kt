package com.diffuse.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.diffuse.feature.editor.tools.auto.AutoSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import com.diffuse.feature.editor.tools.style.StyleSheet
import com.diffuse.feature.editor.tools.crop.CropSheet
import com.diffuse.feature.editor.tools.crop.STRAIGHTEN_MAX_DEG
import com.diffuse.feature.editor.tools.direct.DirectSheet
import com.diffuse.feature.editor.tools.expand.ExpandOverlay
import com.diffuse.feature.editor.tools.expand.ExpandSheet
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
    val speechState by viewModel.speech.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val document = state.document

    var toolLevel by rememberSaveable { mutableStateOf(ToolGroup.Root) }
    var armDirectMic by rememberSaveable { mutableStateOf(false) }

    val selectedTool = state.selectedTool
    LaunchedEffect(selectedTool) {
        if (selectedTool == null) toolLevel = ToolGroup.Root
    }

    BackHandler(enabled = toolLevel != ToolGroup.Root) { toolLevel = ToolGroup.Root }

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
            overlayTransform = overlayTransform(state),
            disabledTools = disabledTools(state),
            toolLevel = toolLevelState(toolLevel, state) { toolLevel = it },
            gestureMode = if (state.selectedTool == Tool.Select) CanvasGestureMode.SelectPoint else CanvasGestureMode.Pan,
            pointTaps = if (state.selectedTool != Tool.Select) null else CanvasPointTaps(
                onForeground = { viewModel.selection.addPoint(it.x, it.y, foreground = true) },
                onBackground = { viewModel.selection.addPoint(it.x, it.y, foreground = false) },
            ),
            busy = isBusy(state),
            busyLabelRes = busyLabel(state),
            onCancelWork = { cancelWork(viewModel) },
            message = message(state),
            onMessageShown = { clearMessages(viewModel) },
            canvasOverlay = canvasOverlay(state, viewModel),
            sheet = sheetFor(state, document, viewModel, armDirectMic) { armDirectMic = false },
            onVibeListen = { listening -> applyVibeSpeech(listening, viewModel) { armDirectMic = it } },
            vibeTranscript = state.direct.request,
            vibeStatus = vibePlannerStatus(state),
            speechState = speechState,
        )
    }
}

private fun toolLevelState(level: ToolGroup, state: EditorUiState, onChange: (ToolGroup) -> Unit) =
    ToolLevelState(level, onChange, menuProfileFor(state.portrait))

private fun cancelWork(viewModel: EditorViewModel) {
    viewModel.selection.cancelWork()
    viewModel.erase.cancel()
    viewModel.fill.cancel()
    viewModel.expand.cancel()
    viewModel.auto.cancel()
    viewModel.direct.cancelWork()
}

private fun clearMessages(viewModel: EditorViewModel) {
    viewModel.selection.onMessageShown()
    viewModel.erase.onMessageShown()
    viewModel.fill.onMessageShown()
    viewModel.expand.onMessageShown()
    viewModel.auto.onMessageShown()
    viewModel.style.onMessageShown()
    viewModel.direct.onMessageShown()
}

private fun isBusy(state: EditorUiState): Boolean =
    state.selection.working || state.erase.busy || state.fill.busy || state.expand.busy ||
        state.auto.busy || state.style.matching || state.direct.working

private fun disabledTools(state: EditorUiState): Set<Tool> = buildSet {
    if (!state.selection.enabled) add(Tool.Select)
    if (!state.erase.enabled || state.document?.activeMaskId == null) add(Tool.Erase)
    if (!state.fill.enabled || state.document?.activeMaskId == null) add(Tool.Fill)
    if (!state.expand.enabled || state.document?.canOutpaint == false) add(Tool.Expand)
    if (!state.auto.enabled) add(Tool.Auto)
    if (!state.direct.enabled) add(Tool.Direct)
}

private fun busyLabel(state: EditorUiState): Int = when {
    state.direct.planning -> R.string.direct_planning
    state.direct.running -> R.string.direct_running
    state.erase.busy -> R.string.erase_working
    state.expand.busy -> R.string.expand_working
    state.fill.busy -> R.string.fill_working
    state.auto.busy -> R.string.auto_working
    state.style.matching -> R.string.style_matching
    state.selection.phraseBusy -> R.string.select_prompt_working
    else -> R.string.select_preparing
}

@Composable
private fun message(state: EditorUiState): String? {
    val direct = state.direct.message
    return when {
        direct?.arg != null -> stringResource(direct.res, direct.arg)
        direct != null -> stringResource(direct.res)
        else -> (state.selection.message ?: state.erase.message ?: state.fill.message
            ?: state.expand.message ?: state.auto.message ?: state.style.message)?.let { stringResource(it) }
    }
}

@Composable
private fun canvasOverlay(state: EditorUiState, viewModel: EditorViewModel): (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = when (state.selectedTool) {
    Tool.Crop -> cropOverlaySlot(rect = state.cropState.rect, onRectChange = { viewModel.onCropChange(state.cropState.copy(rect = it)) }, aspect = state.cropState.preset)
    Tool.Select -> selectionOverlaySlot(mask = state.selection.mask, points = state.selection.points, labels = state.selection.labels)
    Tool.Expand -> {{ ExpandOverlay(margins = state.expand.margins, onMarginsChange = viewModel.expand::setMargins) }}
    null -> null
    else -> state.activeMask?.takeIf { state.maskedAdjust }?.let { selectionOverlaySlot(mask = it, points = emptyList(), labels = emptyList()) }
}

@Composable
private fun sheetFor(state: EditorUiState, document: com.diffuse.core.imaging.model.EditDocument?, viewModel: EditorViewModel, armDirectMic: Boolean = false, onDirectMicArmed: () -> Unit = {}): (@Composable () -> Unit)? {
    if (state.selection.showSettings) {
        return { Sam3SettingsSheet(config = state.selection.config, geminiApiKey = state.selection.geminiApiKey, monetConfig = state.selection.monetConfig, onSave = viewModel.selection::saveSettings, onCancel = { viewModel.selection.setSettingsVisible(false) }) }
    }
    return document?.let { doc ->
        {
            when (state.selectedTool) {
                Tool.Crop -> CropToolSheet(state = state, viewModel = viewModel)
                Tool.Select -> SelectSheet(state = state.selection, onModeChange = viewModel.selection::setMode, onInvert = viewModel.selection::invert, onClear = viewModel.selection::clear, onCutOut = { viewModel.applySelection(cutOut = true) }, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet, promptBar = { VoicePromptBar(value = state.selection.phrase, onValueChange = viewModel.selection::setPhrase, onSubmit = viewModel.selection::submitPhrase, speech = viewModel.speech, enabled = !state.selection.phraseBusy, onMessage = viewModel.selection::showMessage) })
                Tool.Fill -> FillToolSheet(state = state, viewModel = viewModel)
                Tool.Expand -> ExpandToolSheet(state = state, viewModel = viewModel)
                Tool.Auto -> AutoToolSheet(state = state, viewModel = viewModel)
                Tool.Style -> StyleToolSheet(state = state, viewModel = viewModel)
                Tool.Direct -> DirectToolSheet(state = state, viewModel = viewModel, armMic = armDirectMic, onMicArmed = onDirectMicArmed)
                else -> ToolSheetHost(maskOption = MaskOption(available = doc.activeMaskId != null, maskedOnly = state.maskedAdjust, onMaskedOnlyChange = viewModel::onMaskedAdjustChange), selectedTool = state.selectedTool, document = doc, onValueChange = viewModel::onAdjust, onValueChangeFinished = viewModel::onAdjustFinished, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet)
            }
        }
    }
}

private fun overlayTransform(state: EditorUiState): OverlayTransform = when (state.selectedTool) {
    Tool.Crop -> OverlayTransform(quarterTurns = state.cropState.quarterTurns, straightenDeg = state.cropState.straightenDeg)
    Tool.Expand -> OverlayTransform(margins = state.expand.margins)
    else -> OverlayTransform.None
}

private fun sourceAspect(state: EditorUiState): Float {
    val source = state.source
    return if (source == null || source.height <= 0) 1f else source.width.toFloat() / source.height
}

@Composable
private fun FillToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    FillSheet(state = state.fill, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet, promptBar = { VoicePromptBar(value = state.fill.prompt, onValueChange = viewModel.fill::setPrompt, onSubmit = { viewModel.applySheet() }, speech = viewModel.speech, placeholder = stringResource(R.string.fill_placeholder), enabled = !state.fill.busy, onMessage = viewModel.fill::showMessage) })
}

@Composable
private fun ExpandToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    ExpandSheet(state = state.expand, sourceAspect = sourceAspect(state), onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet)
}

@Composable
private fun StyleToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { chosen ->
            decodeReference(context, chosen)?.let { reference ->
                viewModel.style.matchReference(reference = reference, image = state.preview?.asAndroidBitmap(), document = state.document)
            } ?: viewModel.style.showFailure()
        }
    }
    StyleSheet(state = state.style, onSelect = viewModel.style::select, onVariantSelect = viewModel.style::selectVariant, onIntensityChange = viewModel.style::setIntensity, onPickReference = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet)
}

private fun decodeReference(context: android.content.Context, uri: android.net.Uri) =
    runCatching { context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()

@Composable
private fun AutoToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    AutoSheet(state = state.auto, onStyleChange = viewModel.auto::setStyle, onIntensityChange = viewModel.auto::setIntensity, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet)
}

@Composable
private fun DirectToolSheet(state: EditorUiState, viewModel: EditorViewModel, armMic: Boolean = false, onMicArmed: () -> Unit = {}) {
    DirectSheet(state = state.direct, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet, promptBar = { VoicePromptBar(value = state.direct.request, onValueChange = viewModel.direct::setRequest, onSubmit = viewModel.direct::submit, speech = viewModel.speech, placeholder = stringResource(R.string.direct_placeholder), enabled = !state.direct.working, onMessage = viewModel.direct::showMessage, armMic = armMic, onArmConsumed = onMicArmed) })
}

@Composable
private fun CropToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    CropSheet(preset = state.cropState.preset, straightenDeg = state.cropState.straightenDeg, onPresetChange = { preset -> viewModel.onCropChange(state.cropState.withPreset(preset)) }, onStraightenChange = { degrees -> viewModel.onCropChange(state.cropState.straightened(degrees.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG))) }, onStraightenFinished = {}, onRotate = { quarters -> viewModel.onCropChange(state.cropState.rotated(quarters)) }, onCancel = viewModel::cancelSheet, onApply = viewModel::applySheet)
}

@Composable
private fun vibePlannerStatus(state: EditorUiState): String {
    val direct = state.direct
    val message = direct.message
    return when {
        direct.planning -> stringResource(R.string.direct_planning)
        direct.running -> stringResource(R.string.direct_running)
        direct.notUnderstood -> stringResource(R.string.direct_not_understood)
        message?.arg != null -> stringResource(message.res, message.arg)
        message != null -> stringResource(message.res)
        else -> ""
    }
}

private fun applyVibeSpeech(listening: Boolean, viewModel: EditorViewModel, onArmMic: (Boolean) -> Unit) {
    val command = vibeSpeechCommand(listening)
    if (command.openDirect) viewModel.onToolClick(Tool.Direct)
    onArmMic(command.startSpeech)
    if (command.stopSpeech) viewModel.speech.stop()
}
