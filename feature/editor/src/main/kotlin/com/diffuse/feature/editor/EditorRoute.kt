package com.diffuse.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.diffuse.feature.editor.tools.retouch.SkinRetouchActions
import com.diffuse.feature.editor.tools.retouch.SkinRetouchSheet
import com.diffuse.feature.editor.tools.select.SelectSheet
import com.diffuse.core.imaging.render.CanvasPoint
import com.diffuse.feature.editor.tools.multishot.MultiShotActions
import com.diffuse.feature.editor.tools.multishot.MultiShotOverlay
import com.diffuse.feature.editor.tools.multishot.MultiShotSheet
import com.diffuse.feature.editor.tools.multishot.MultiShotState
import com.diffuse.feature.editor.tools.multishot.ShotStatus
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

    // specs/tool_groups.md §3: the open level is UI state, not document state, and it resets to
    // `Root` on every entry to the screen — a user coming back to a photo sees the whole app.
    var toolLevel by rememberSaveable { mutableStateOf(ToolGroup.Root) }

    // §4: committing or cancelling a sheet returns to the root, because the next thing a user does
    // is usually not another AI call. Keyed on the sheet **closing**, so opening one does not, and
    // so a disabled child — which opens nothing — leaves the level alone.
    val selectedTool = state.selectedTool
    LaunchedEffect(selectedTool) {
        if (selectedTool == null) toolLevel = ToolGroup.Root
    }

    // §4: system back closes the level before it leaves the screen.
    BackHandler(enabled = toolLevel != ToolGroup.Root) { toolLevel = ToolGroup.Root }

    Box(modifier = modifier.fillMaxSize()) {
        EditorScreen(
            preview = shownPreview(state),
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
            onExport = exportAction(state, viewModel, onExport),
            overlayTransform = overlayTransform(state),
            disabledTools = disabledTools(state),
            toolLevel = toolLevelState(toolLevel, state) { toolLevel = it },
            gestureMode = if (pointTaps(state, viewModel) != null) {
                CanvasGestureMode.SelectPoint
            } else {
                CanvasGestureMode.Pan
            },
            pointTaps = pointTaps(state, viewModel),
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

/**
 * work/decisions.md T79: what the strip is bound to — the level the user opened, and the ordering
 * the photograph earned.
 *
 * The profile only reorders, so a detection that lands while a sheet is open changes nothing the
 * user is looking at: the sheet stays open, the selected tool stays selected, and the new order is
 * there underneath it. A file-level function rather than four lines inline, because `EditorRoute`
 * is at detekt's method-length limit.
 */
private fun toolLevelState(
    level: ToolGroup,
    state: EditorUiState,
    onChange: (ToolGroup) -> Unit,
) = ToolLevelState(level, onChange, menuProfileFor(state.portrait))

/**
 * specs/multishot.md §2: while an added photo is still a photo, the canvas shows it rather than
 * the composite.
 */
private fun shownPreview(state: EditorUiState) =
    state.multiShot.photo?.takeIf { state.selectedTool == Tool.MultiShot } ?: state.preview

/**
 * specs/selection_tool.md §2 and multishot.md §2: the tools that claim a single-finger tap on the
 * photo — 선택, and 멀티샷 while an added photo's selection is being refined.
 */
private fun pointTaps(state: EditorUiState, viewModel: EditorViewModel): CanvasPointTaps? = when {
    state.selectedTool == Tool.Select -> CanvasPointTaps(
        onForeground = { viewModel.selection.addPoint(it.x, it.y, foreground = true) },
        onBackground = { viewModel.selection.addPoint(it.x, it.y, foreground = false) },
    )
    state.selectedTool == Tool.MultiShot && state.multiShot.selected?.status ==
        ShotStatus.Selecting -> CanvasPointTaps(
        onForeground = { viewModel.multiShot.addPoint(it.x, it.y, include = true) },
        onBackground = { viewModel.multiShot.addPoint(it.x, it.y, include = false) },
    )
    else -> null
}

/** DESIGN.md §7: the overlay's cancel button reaches whichever tool is working. */
private fun cancelWork(viewModel: EditorViewModel) {
    viewModel.selection.cancelWork()
    viewModel.erase.cancel()
    viewModel.fill.cancel()
    viewModel.expand.cancel()
    viewModel.auto.cancel()
    viewModel.direct.cancelWork()
    viewModel.skin.cancelWork()
    viewModel.multiShot.cancelWork()
}

/** specs/skin_retouch.md §5: while a skin draft is open, export waits for 적용 or 취소. */
private fun exportAction(state: EditorUiState, viewModel: EditorViewModel, onExport: () -> Unit): () -> Unit =
    if (state.selectedTool == Tool.SkinRetouch) {
        { viewModel.skin.showMessage(R.string.skin_retouch_export_blocked) }
    } else {
        onExport
    }

/** One snackbar, so the one that was shown is cleared wherever it came from. */
private fun clearMessages(viewModel: EditorViewModel) {
    viewModel.selection.onMessageShown()
    viewModel.erase.onMessageShown()
    viewModel.fill.onMessageShown()
    viewModel.expand.onMessageShown()
    viewModel.auto.onMessageShown()
    viewModel.style.onMessageShown()
    viewModel.direct.onMessageShown()
    viewModel.skin.onMessageShown()
    viewModel.multiShot.onMessageShown()
}

/** DESIGN.md §7: every AI call shows progress and a way out, so they share one flag. */
private fun isBusy(state: EditorUiState): Boolean =
    state.selection.working || state.erase.busy || state.fill.busy || state.expand.busy ||
        state.auto.busy || state.style.matching || state.direct.working || state.skin.busy ||
        state.multiShot.working

/** specs/selection_tool.md §1 and generative_erase.md §5: a tool that cannot work is greyed. */
private fun disabledTools(state: EditorUiState): Set<Tool> = buildSet {
    if (!state.selection.enabled) add(Tool.Select)
    if (!state.erase.enabled || state.document?.activeMaskId == null) add(Tool.Erase)
    // specs/generative_fill.md §6: the same two reasons, and the same greyed-but-tappable rule.
    if (!state.fill.enabled || state.document?.activeMaskId == null) add(Tool.Fill)
    // specs/outpaint.md §6: the key, and the document's own mask-op guard.
    if (!state.expand.enabled || state.document?.canOutpaint == false) add(Tool.Expand)
    // specs/auto_enhance.md §6: the probe alone. 자동 needs nothing from the document.
    if (!state.auto.enabled) add(Tool.Auto)
    // specs/vibe_edit.md §10: the key alone. A plan with no `Select` needs no SAM 3 server.
    if (!state.direct.enabled) add(Tool.Direct)
}

/** DESIGN.md §4 State display: the overlay says what is actually happening. */
private fun busyLabel(state: EditorUiState): Int = when {
    state.multiShot.working && state.multiShot.workLabel != null -> state.multiShot.workLabel
    state.direct.planning -> R.string.direct_planning
    state.direct.running -> R.string.direct_running
    state.erase.busy -> R.string.erase_working
    state.expand.busy -> R.string.expand_working
    state.fill.busy -> R.string.fill_working
    state.auto.busy -> R.string.auto_working
    state.style.matching -> R.string.style_matching
    state.skin.preparing -> R.string.skin_retouch_working
    state.skin.applying -> R.string.skin_retouch_applying
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
        else -> (
            state.selection.message ?: state.erase.message ?: state.fill.message
                ?: state.expand.message ?: state.auto.message ?: state.style.message ?: state.skin.message
                ?: state.multiShot.message
            )?.let { stringResource(it) }
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
    // specs/outpaint.md §6: the pending area is the canvas's own checkerboard, drawn by
    // `OverlayTransform.margins`; the overlay itself is the four handles.
    // specs/multishot.md §2: the added photo's selection while it is a photo, the drag once placed.
    Tool.MultiShot -> multiShotOverlay(state, viewModel)
    Tool.Expand -> {
        {
            ExpandOverlay(
                margins = state.expand.margins,
                onMarginsChange = viewModel.expand::setMargins,
            )
        }
    }
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
        return { SettingsSheet(state, viewModel) }
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
                    onCutOut = { viewModel.applySelection(cutOut = true) },
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
                Tool.Expand -> ExpandToolSheet(state = state, viewModel = viewModel)
                Tool.Auto -> AutoToolSheet(state = state, viewModel = viewModel)
                Tool.Style -> StyleToolSheet(state = state, viewModel = viewModel)
                Tool.Direct -> DirectToolSheet(state = state, viewModel = viewModel)
                Tool.SkinRetouch -> SkinRetouchToolSheet(state = state, viewModel = viewModel)
                Tool.MultiShot -> MultiShotToolSheet(state = state, viewModel = viewModel)
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

/**
 * The canvas-level previews neither tool has committed yet: 자르기's rotation (T24) and 확대's
 * pending margins (outpaint.md §6). Cancel closes the sheet, which removes both with it.
 */
private fun overlayTransform(state: EditorUiState): OverlayTransform = when (state.selectedTool) {
    Tool.Crop -> OverlayTransform(
        quarterTurns = state.cropState.quarterTurns,
        straightenDeg = state.cropState.straightenDeg,
    )
    Tool.Expand -> OverlayTransform(margins = state.expand.margins)
    else -> OverlayTransform.None
}

/** The bare source's shape: what outpaint.md §6's ratio readout is measured against. */
private fun sourceAspect(state: EditorUiState): Float {
    val source = state.source
    return if (source == null || source.height <= 0) 1f else source.width.toFloat() / source.height
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

/** specs/outpaint.md §6: no prompt bar — 확대 continues a scene the model can already see. */
@Composable
private fun ExpandToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    ExpandSheet(
        state = state.expand,
        sourceAspect = sourceAspect(state),
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
    )
}

/**
 * specs/style_match.md §4, §5: tiles of the user's own photograph, one 강도 slider, and the pill
 * that hands a reference photograph in. The picker is the system one, so nothing is granted and
 * nothing is stored — §10's rule falls out of using it.
 */
@Composable
private fun StyleToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { chosen ->
            // The controller is called straight from here: `EditorViewModel` is at detekt's
            // function ceiling (T65, T78), and this reads the same state the route already holds.
            decodeReference(context, chosen)?.let { reference ->
                viewModel.style.matchReference(
                    reference = reference,
                    image = state.preview?.asAndroidBitmap(),
                    document = state.document,
                )
            } ?: viewModel.style.showFailure()
        }
    }
    StyleSheet(
        state = state.style,
        onSelect = viewModel.style::select,
        onVariantSelect = viewModel.style::selectVariant,
        onIntensityChange = viewModel.style::setIntensity,
        onPickReference = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
    )
}

/**
 * Decoded here rather than in the ViewModel: it is one `ContentResolver` read of a `Uri` the
 * picker just handed this composable, and routing it through the graph would add a dependency for
 * a bitmap that lives for one call (§10).
 */
private fun decodeReference(context: android.content.Context, uri: android.net.Uri) =
    runCatching {
        context.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    }.getOrNull()

/**
 * specs/auto_enhance.md §6: the sheet arrives **after** the call, holding the result. Changing a
 * chip costs another call; the 강도 slider costs none, because it scales a plan already here.
 */
@Composable
private fun AutoToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    AutoSheet(
        state = state.auto,
        onStyleChange = viewModel.auto::setStyle,
        onIntensityChange = viewModel.auto::setIntensity,
        onCancel = viewModel::cancelSheet,
        onApply = viewModel::applySheet,
    )
}

@Composable
private fun SettingsSheet(state: EditorUiState, viewModel: EditorViewModel) {
    Sam3SettingsSheet(
        config = state.selection.config,
        geminiApiKey = state.selection.geminiApiKey,
        monetConfig = state.selection.monetConfig,
        onSave = viewModel.selection::saveSettings,
        onCancel = { viewModel.selection.setSettingsVisible(false) },
        retouchConfig = state.selection.retouchConfig,
        onSaveRetouch = viewModel.selection::saveRetouchSettings,
    )
}

/** specs/skin_retouch.md §4: the face, four strengths, 미리보기 and the server's own settings path. */
@Composable
private fun SkinRetouchToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    val skin = viewModel.skin
    SkinRetouchSheet(
        state = state.skin,
        actions = SkinRetouchActions(
            onSelectFace = skin::selectFace,
            onStrengthChange = skin::setStrength,
            onPreview = skin::preview,
            onRetryAnalysis = skin::retryAnalysis,
            onRecheckServer = skin::refreshServer,
            onOpenSettings = { viewModel.selection.setSettingsVisible(true) },
            onCancel = viewModel::cancelSheet,
            onApply = viewModel::applySheet,
        ),
    )
}

private fun multiShotOverlay(
    state: EditorUiState,
    viewModel: EditorViewModel,
): (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? {
    val multiShot = state.multiShot
    return when {
        multiShot.photo != null ->
            selectionOverlaySlot(mask = multiShot.mask, points = emptyList(), labels = emptyList())
        multiShot.layoutShown || multiShot.selected?.status == ShotStatus.Ready -> {
            {
                MultiShotOverlay(
                    onMove = viewModel.multiShot::moveBy,
                    marker = multiShot.anchorMarker,
                    anchorEditing = multiShot.anchorEditing,
                    pathStart = multiShot.pathStart,
                    baseline = multiShot.showsBaseline,
                    pathEnd = multiShot.heroAnchor?.let { CanvasPoint(it.x, it.y) },
                    slots = multiShot.slots,
                )
            }
        }
        else -> null
    }
}

/**
 * specs/multishot.md §2: the system Photo Picker, so no gallery permission is asked for — several
 * new photos at once in the time layout, up to what still fits; one for the free layout, a
 * replacement or the last free place. Which request the answer belongs to survives a configuration
 * change; the controller drops an answer to any other request.
 */
@Composable
private fun MultiShotToolSheet(state: EditorUiState, viewModel: EditorViewModel) {
    val multiShot = viewModel.multiShot
    var requestId by rememberSaveable { mutableStateOf<String?>(null) }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        multiShot.onPicked(requestId, listOfNotNull(uri))
    }
    // The contract carries its limit; PickMultipleVisualMedia needs at least two.
    val room = (MultiShotState.MAX_ITEMS - state.multiShot.items.size).coerceAtLeast(2)
    val multipleContract = remember(room) { ActivityResultContracts.PickMultipleVisualMedia(room) }
    val multiple = rememberLauncherForActivityResult(multipleContract) { uris ->
        multiShot.onPicked(requestId, uris)
    }
    val pick = { key: String? ->
        multiShot.requestPick(key)?.let { request ->
            requestId = request.id
            val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            if (request.max > 1) multiple.launch(images) else single.launch(images)
        }
    }
    MultiShotSheet(
        state = state.multiShot,
        actions = MultiShotActions(
            onAdd = { pick(null) },
            onReplace = { pick(it) },
            onSelect = multiShot::select,
            onRemove = multiShot::remove,
            onMove = multiShot::move,
            onExtract = multiShot::extract,
            onChooseCandidate = multiShot::chooseCandidate,
            onPhraseChange = multiShot::setPhrase,
            onSubmitPhrase = multiShot::submitPhrase,
            onFinishExtraction = multiShot::finishExtraction,
            onOpacityChange = multiShot::setOpacity,
            onScaleChange = multiShot::setScale,
            onRotationChange = multiShot::setRotation,
            onResetPlacement = multiShot::resetPlacement,
            onAfterimage = multiShot::applyAfterimage,
            onRecheckServer = multiShot::refreshServer,
            onOpenSettings = { viewModel.selection.setSettingsVisible(true) },
            onCancel = viewModel::cancelSheet,
            onApply = viewModel::applySheet,
            onModeChange = multiShot::setMode,
            onMoveInTime = multiShot::moveInTime,
            onReverseTime = multiShot::reverseTime,
            onRun = multiShot::runAll,
            onCancelRun = multiShot::cancelRun,
            onPlace = multiShot::placeByOrder,
            onKeepPositions = multiShot::keepPositions,
            onPanelChange = multiShot::setPanel,
            onArrangementChange = multiShot::setArrangement,
            onSpacingChange = multiShot::setSpacing,
            onDirectionChange = multiShot::setDirection,
            onDistanceChange = multiShot::setDistance,
            onStrengthChange = multiShot::setStrength,
            onAnchorEditingChange = multiShot::setAnchorEditing,
            onReselectHero = multiShot::reselectHero,
        ),
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
