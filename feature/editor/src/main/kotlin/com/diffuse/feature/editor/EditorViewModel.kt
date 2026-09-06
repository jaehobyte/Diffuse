package com.diffuse.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.data.ProjectAutosave
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.tools.ToolTap
import com.diffuse.feature.editor.tools.generativeInput
import android.content.Context
import com.diffuse.core.imaging.style.StyleCatalog
import com.diffuse.feature.editor.tools.style.StyleController
import com.diffuse.feature.editor.tools.style.StyleState
import dagger.hilt.android.qualifiers.ApplicationContext
import com.diffuse.feature.editor.tools.auto.AutoController
import com.diffuse.feature.editor.tools.auto.AutoState
import com.diffuse.feature.editor.tools.crop.CropState
import com.diffuse.core.ai.CropRatio
import com.diffuse.feature.editor.tools.crop.preset
import com.diffuse.feature.editor.tools.direct.DirectCanvas
import com.diffuse.feature.editor.tools.direct.DirectController
import com.diffuse.feature.editor.tools.direct.DirectHost
import com.diffuse.feature.editor.tools.direct.DirectState
import com.diffuse.feature.editor.tools.direct.DirectTap
import com.diffuse.feature.editor.tools.direct.PlanRunner
import com.diffuse.feature.editor.tools.erase.EraseCommit
import com.diffuse.feature.editor.tools.erase.EraseController
import com.diffuse.feature.editor.tools.erase.EraseState
import com.diffuse.feature.editor.tools.expand.ExpandController
import com.diffuse.feature.editor.tools.expand.ExpandState
import com.diffuse.feature.editor.tools.fill.FillCommit
import com.diffuse.feature.editor.tools.fill.FillController
import com.diffuse.feature.editor.tools.fill.FillState
import com.diffuse.feature.editor.tools.select.SelectionController
import com.diffuse.feature.editor.tools.select.SelectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** specs/editor_shell.md §State. */
data class EditorUiState(
    val preview: ImageBitmap? = null,
    val source: ImageBitmap? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canCompare: Boolean = false,
    val canReset: Boolean = false,
    val selectedTool: Tool? = null,
    val cropState: CropState = CropState(),
    val document: EditDocument? = null,
    /** specs/selection_tool.md §3: sheet state; nothing here is in the document until Apply. */
    val selection: SelectionState = SelectionState(),
    /** specs/generative_erase.md §5: the tool has no sheet, so this is all its state. */
    val erase: EraseState = EraseState(),
    /** specs/generative_fill.md §6: the prompt lives here between typing it and 적용. */
    val fill: FillState = FillState(),
    /** specs/outpaint.md §6: the pending margins live here between the drag and 적용. */
    val expand: ExpandState = ExpandState(),
    /** specs/auto_enhance.md §6: the model's plan lives here between the call and 적용. */
    val auto: AutoState = AutoState(),

    /** specs/style_match.md §4: the catalog, its tiles, and the style the user is trying on. */
    val style: StyleState = StyleState(),
    /** specs/vibe_edit.md §3: the plan lives here between the response and 적용. */
    val direct: DirectState = DirectState(),
    /** specs/selection_tool.md §8.1: default on, so an adjustment lands where the user looked. */
    val maskedAdjust: Boolean = true,
    /** The applied mask, resolved for the scrim the adjust sheets show. */
    val activeMask: Bitmap? = null,
)

/** specs/editor_shell.md: one ViewModel per screen, UI sends intents, VM reduces to state. */
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: ProjectRepository,
    private val renderer: Renderer,
    ai: EditorAi,
    dispatchers: DispatcherProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** specs/editor_shell.md: the id lives in SavedStateHandle so process death can restore. */
    private val projectId: String = requireNotNull(savedStateHandle[PROJECT_ID]) {
        "EditorViewModel needs a $PROJECT_ID"
    }

    private val autosave = ProjectAutosave(repository)
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var history: HistoryStack? = null

    /** specs/editor_shell.md: a sheet snapshots the document so Cancel can restore it. */
    private var sheetBaseline: EditDocument? = null
    private var previewJob: Job? = null

    /** specs/selection_tool.md: the tool owns its own state, session and undo stack. */
    val selection = SelectionController(ai.segmentation, ai.sam3Settings, ai.geminiSettings, viewModelScope)

    /** specs/prompt_input.md §3: handed straight to the prompt bar; the VM never drives it. */
    val speech: SpeechInput = ai.speech

    /** specs/generative_erase.md §10, T50: one commit shape for both erase paths. */
    private val eraseCommit = EraseCommit(
        saveMask = { maskId, mask -> repository.saveMask(projectId, maskId, mask) },
        saveResult = { eraseId, result -> repository.saveEraseResult(projectId, eraseId, result) },
    )

    /** specs/generative_erase.md: runs the model; this class is what pushes the result. */
    val erase = EraseController(ai.erase, eraseCommit, viewModelScope)

    /** T67: one commit shape for both fill paths, the way `EraseCommit` serves both erase ones. */
    private val fillCommit = FillCommit(
        saveMask = { maskId, mask -> repository.saveMask(projectId, maskId, mask) },
        saveResult = { fillId, result -> repository.saveFillResult(projectId, fillId, result) },
    )

    /** specs/generative_fill.md §6: same split — the tool runs it, this class commits it. */
    val fill = FillController(ai.fill, fillCommit, viewModelScope)

    /** specs/outpaint.md §6: same split again — the tool runs it, this class commits it. */
    val expand = ExpandController(
        provider = ai.outpaint,
        saveResult = { outpaintId, result ->
            repository.saveOutpaintResult(projectId, outpaintId, result)
        },
        scope = viewModelScope,
    )

    /** specs/auto_enhance.md §6: the tool has no sheet before its call, and one after it. */
    val auto = AutoController(ai.autoEnhance, viewModelScope)

    /**
     * specs/style_match.md §4. 스타일 calls nothing, so it needs no provider — what it needs is
     * the catalog, which is an asset, and the renderer that draws its tiles.
     */
    val style = StyleController(
        catalog = { withContext(dispatchers.io) { StyleCatalog.load(context.assets) } },
        renderer = renderer,
        scope = viewModelScope,
    )

    /**
     * specs/vibe_edit.md §3, §9. The tool owns the plan and the run; what it cannot know is
     * which project is open, what the canvas is showing, and where history lives, so those
     * four arrive as lambdas — the shape `EraseController` already uses for its save.
     */
    val direct = DirectController(
        provider = ai.plan,
        runner = PlanRunner(
            segmentation = ai.segmentation,
            erase = ai.erase,
            fill = ai.fill,
            dispatchers = dispatchers,
            saveMask = { maskId, mask -> repository.saveMask(projectId, maskId, mask) },
            fillCommit = fillCommit,
            eraseCommit = eraseCommit,
            // T70: per step, not per run — each step chains a new document, and the frame a
            // generative step is shown has to be the one it is actually editing.
            generativeInput = { document ->
                generativeInput(renderer, document, PREVIEW_LONG_EDGE_PX)
            },
        ),
        scope = viewModelScope,
        host = object : DirectHost {
            override fun canvas(): DirectCanvas? {
                val state = _uiState.value
                val document = state.document
                val preview = state.preview?.asAndroidBitmap()
                return if (document == null || preview == null) {
                    null
                } else {
                    DirectCanvas(document, preview, state.activeMask, sourceAspect(state))
                }
            }

            override fun commit(document: EditDocument) {
                history?.push(document)
            }

            override suspend fun releaseSession() = selection.release()

            /**
             * specs/vibe_edit.md §4.1: a plan that ended with a crop hands off to the 자르기
             * tool, which opens on the rect that was just committed. The model chose the ratio;
             * the user chooses the framing.
             */
            override fun onFinished(cropRatio: CropRatio?) {
                sheetBaseline = null
                _uiState.value = _uiState.value.copy(selectedTool = null)
                if (cropRatio != null) {
                    onToolClick(Tool.Crop)
                    // The rect is already at this ratio; selecting the chip keeps it there while
                    // the user drags, which is the whole point of having chosen a ratio.
                    _uiState.value = _uiState.value.copy(
                        cropState = _uiState.value.cropState.withPreset(cropRatio.preset),
                    )
                }
            }
        },
    )

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            selection.state.collect { _uiState.value = _uiState.value.copy(selection = it) }
        }
        viewModelScope.launch {
            erase.state.collect { _uiState.value = _uiState.value.copy(erase = it) }
        }
        viewModelScope.launch {
            fill.state.collect { _uiState.value = _uiState.value.copy(fill = it) }
        }
        viewModelScope.launch {
            expand.state.collect { _uiState.value = _uiState.value.copy(expand = it) }
        }
        // T69: opening or closing 자르기 changes what the preview should show without changing
        // the document, so the transition is what is collected — one place rather than a call at
        // the end of `onToolClick`, `cancelSheet` and `applySheet`, one of which would be missed.
        viewModelScope.launch {
            _uiState.map { it.selectedTool == Tool.Crop }
                .distinctUntilChanged()
                .collect { _uiState.value.document?.let(::requestPreview) }
        }
        viewModelScope.launch {
            auto.state.collect { _uiState.value = _uiState.value.copy(auto = it) }
        }
        // specs/auto_enhance.md §6: the plan applies live while the sheet is open, so 강도 is a
        // slider on a result the user is already looking at. Same shape as T69's collector, and
        // for the same reason: what the preview should show changed without the document changing.
        viewModelScope.launch {
            _uiState.map { if (it.selectedTool == Tool.Auto) it.auto.scaled() else null }
                .distinctUntilChanged()
                .collect { _uiState.value.document?.let(::requestPreview) }
        }
        viewModelScope.launch {
            style.state.collect { _uiState.value = _uiState.value.copy(style = it) }
        }
        // specs/style_match.md §4: selecting a tile applies live. T77's collector shape again —
        // what the preview should show changed without the document changing.
        viewModelScope.launch {
            _uiState.map { if (it.selectedTool == Tool.Style) it.style.scaled() else null }
                .distinctUntilChanged()
                .collect { _uiState.value.document?.let(::requestPreview) }
        }
        viewModelScope.launch {
            direct.state.collect { _uiState.value = _uiState.value.copy(direct = it) }
        }
    }

    private suspend fun load() {
        val document = when (val loaded = repository.load(projectId)) {
            is Result.Failure -> return
            is Result.Success -> loaded.value
        }
        val stack = HistoryStack(document)
        history = stack
        _uiState.value = _uiState.value.copy(document = document)
        renderSource(document)
        observe(stack)
    }

    private fun observe(stack: HistoryStack) {
        viewModelScope.launch {
            stack.current.collect { document ->
                _uiState.value = _uiState.value.copy(
                    document = document,
                    canUndo = stack.canUndo.value,
                    canRedo = stack.canRedo.value,
                    canCompare = document.canCompare(),
                    canReset = document.operations.isNotEmpty(),
                )
                requestPreview(document)
            }
        }
        // specs/persistence.md: autosave 2s after the last operation.
        viewModelScope.launch { autosave.run(stack.current) }
    }

    /**
     * specs/render.md wants a new preview to supersede the one in flight; the renderer is
     * cancellable, and the ViewModel owns the scope, so conflation belongs here.
     *
     * T69: while 자르기 is open the `Crop` itself is left out. specs/crop.md says opening the tool
     * refits to the un-cropped source, and rendering the document as it stands puts the overlay on
     * an already-cropped photo — which a plan ending in `crop_ratio` made obvious, because it
     * commits the crop and *then* opens the tool. Nothing else is dropped: an outpaint, an erase
     * and every adjust still show, because the user is framing the photo they actually have.
     *
     * T77: and while 자동 is open the pending plan is added, for the mirror-image reason — the
     * boost is not in the document until 적용, and a sheet whose slider changed nothing on screen
     * would be asking the user to judge a number (auto_enhance.md §6).
     */
    private fun requestPreview(document: EditDocument) {
        previewJob?.cancel()
        val shown = when (_uiState.value.selectedTool) {
            Tool.Crop -> document.copy(
                operations = document.operations.filterNot { it is Operation.Crop },
            )
            Tool.Auto -> _uiState.value.auto.appliedTo(document)
            // specs/style_match.md §4: the same reason as 자동's — the style is not in the
            // document until 적용, and a tile the canvas does not follow is a swatch.
            Tool.Style -> _uiState.value.style.appliedTo(document)
            else -> document
        }
        previewJob = viewModelScope.launch {
            val rendered = renderer.preview(shown, PREVIEW_LONG_EDGE_PX)
            // Resolved here rather than in its own pass: it is cached, and this is the one
            // place that already knows the document changed.
            val mask = document.activeMaskId?.let { renderer.resolveMask(document, it) }
            _uiState.value = _uiState.value.copy(
                preview = (rendered as? Result.Success)?.value?.asImageBitmap()
                    ?: _uiState.value.preview,
                activeMask = mask,
            )
        }
    }

    private suspend fun renderSource(document: EditDocument) {
        val bare = document.copy(operations = emptyList())
        val rendered = renderer.preview(bare, PREVIEW_LONG_EDGE_PX)
        if (rendered is Result.Success) {
            _uiState.value = _uiState.value.copy(source = rendered.value.asImageBitmap())
        }
    }

    fun onToolClick(tool: Tool) {
        val state = _uiState.value
        when {
            state.selectedTool == tool -> cancelSheet()
            tool == Tool.Select && !selection.onToolTapped() -> Unit
            tool in GENERATIVE_TOOLS -> onGenerativeToolTapped(state, tool)
            // specs/vibe_edit.md §10: a blank key opens the 서버 설정 sheet, through the same
            // controller-returns-an-intent shape 지우기 uses. One sheet, one owner.
            tool == Tool.Direct -> when (direct.onToolTapped()) {
                DirectTap.OpenSettings -> selection.setSettingsVisible(true)
                DirectTap.Open -> {
                    sheetBaseline = state.document
                    _uiState.value = state.copy(selectedTool = Tool.Direct)
                }
            }
            else -> {
                sheetBaseline = state.document
                val aspect = sourceAspect(state)
                val document = state.document
                _uiState.value = state.copy(
                    selectedTool = tool,
                    cropState = if (document == null) {
                        CropState()
                    } else {
                        CropState.from(document, aspect)
                    },
                )
                if (tool == Tool.Select) state.preview?.let { selection.open(it.asAndroidBitmap()) }
                // specs/style_match.md §7: the tiles are the user's own photograph, so they can
                // only be drawn once there is a document to draw them from.
                if (tool == Tool.Style) style.open(document)
            }
        }
    }


    /**
     * The three tools that ask a provider for pixels. They share the controller-returns-an-intent
     * shape and differ in what a yes means: 지우기 has no sheet and runs on the tap
     * (generative_erase.md §5, §9), 채우기 opens one because it needs a noun
     * (generative_fill.md §6), and 확대 opens one because it needs margins (outpaint.md §6).
     *
     * What each one asks the *document* differs too — a selection for the first two, and for 확대
     * the absence of one (§3) — so the question is the argument rather than the branch.
     */
    private fun onGenerativeToolTapped(state: EditorUiState, tool: Tool) {
        val hasSelection = state.document?.activeMaskId != null
        val tap = when (tool) {
            Tool.Erase -> erase.onToolTapped(hasSelection)
            Tool.Fill -> fill.onToolTapped(hasSelection)
            Tool.Expand -> expand.onToolTapped(state.document?.canOutpaint == true)
            else -> auto.onToolTapped()
        }
        when (tap) {
            ToolTap.Refused -> Unit
            ToolTap.OpenSettings -> selection.setSettingsVisible(true)
            ToolTap.Open -> {
                // specs/editor_shell.md: the snapshot Cancel restores to.
                sheetBaseline = state.document
                _uiState.value = state.copy(selectedTool = tool)
            }
            // The two that run on the tap. 지우기 commits straight into history
            // (generative_erase.md §5); 자동 opens its sheet on a result rather than on an empty
            // box (auto_enhance.md §6).
            // The eraser is shown the frame without the adjustments; see [eraseInput].
            ToolTap.Run -> if (tool == Tool.Auto) {
                runAuto()
            } else {
                viewModelScope.launch {
                    val document = state.document
                    erase.runAndCommit(
                        image = document?.let {
                            generativeInput(renderer, it, PREVIEW_LONG_EDGE_PX)
                        }
                            ?: state.preview?.asAndroidBitmap(),
                        mask = state.activeMask,
                        document = document,
                        onCommitted = { committed -> history?.push(committed) },
                    )
                }
            }
        }
    }

    /**
     * specs/auto_enhance.md §6. The sheet opens on a **result**, not on an empty box, so the call
     * comes first and `onReady` is what shows it. Changing a chip afterwards is the controller's
     * own re-run, on the bitmap this first call handed it.
     */
    private fun runAuto() {
        auto.run(_uiState.value.preview?.asAndroidBitmap()) {
            sheetBaseline = _uiState.value.document
            _uiState.value = _uiState.value.copy(selectedTool = Tool.Auto)
        }
    }

    fun onAdjust(kind: AdjustKind, value: Float) {
        val stack = history ?: return
        val state = _uiState.value
        // specs/selection_tool.md §8.1: the toggle only means anything with a mask applied.
        val maskId = state.document?.activeMaskId?.takeIf { state.maskedAdjust }
        stack.push(
            stack.current.value.withAdjust(kind, value, maskId),
            coalesceKey = "adjust:$kind:$maskId",
        )
    }

    fun onMaskedAdjustChange(maskedOnly: Boolean) {
        _uiState.value = _uiState.value.copy(maskedAdjust = maskedOnly)
    }

    fun onAdjustFinished() {
        history?.commitCoalesce()
    }

    fun onCropChange(state: CropState) {
        _uiState.value = _uiState.value.copy(cropState = state)
    }

    /** specs/editor_shell.md: Cancel restores the snapshot and adds no history entry. */
    fun cancelSheet() {
        val baseline = sheetBaseline
        val stack = history
        if (baseline != null && stack != null && stack.current.value != baseline) {
            stack.push(baseline)
            stack.commitCoalesce()
        }
        sheetBaseline = null
        selection.closeSheet()
        fill.close()
        expand.close()
        auto.close()
        style.close()
        direct.close()
        _uiState.value = _uiState.value.copy(selectedTool = null)
    }

    fun applySheet() {
        val state = _uiState.value
        when (state.selectedTool) {
            Tool.Select -> applySelection()
            // specs/generative_fill.md §6: 적용 runs the model, and the sheet closes only once
            // the result is committed — a failure leaves it open with the prompt intact.
            //
            // T70: the frame is the one the eraser gets — without the adjustments — because the
            // result carries its own pixels and `FillCommit` puts it under the adjust stack.
            Tool.Fill -> viewModelScope.launch {
                val document = state.document
                fill.runAndCommit(
                    image = document?.let { generativeInput(renderer, it, PREVIEW_LONG_EDGE_PX) },
                    mask = state.activeMask,
                    document = document,
                    onCommitted = { committed ->
                        history?.push(committed)
                        sheetBaseline = null
                        _uiState.value = _uiState.value.copy(selectedTool = null)
                    },
                )
            }
            // specs/outpaint.md §6: the request is built from the **bare source**, not the
            // preview, so a second 확대 re-invents from the photograph rather than from the
            // model's last answer.
            Tool.Expand -> expand.runAndCommit(
                source = state.source?.asAndroidBitmap(),
                document = state.document,
                onCommitted = { document ->
                    history?.push(document)
                    sheetBaseline = null
                    _uiState.value = _uiState.value.copy(selectedTool = null)
                },
            )
            // specs/auto_enhance.md §6: one history entry for the whole boost, so one undo
            // removes it. The plan is already here — 적용 costs no call.
            //
            // The sheet closes **before** the push: the pending plan is what the preview is
            // currently adding on top of the document, so pushing first would render it twice
            // for one frame.
            Tool.Auto -> auto.apply(state.document)?.let { boosted ->
                sheetBaseline = null
                auto.close()
                _uiState.value = _uiState.value.copy(selectedTool = null)
                history?.push(boosted)
            }
            // specs/style_match.md §4: 적용 commits every `Adjust` the preset carries as one
            // history entry, so one undo takes the whole style back. Closed before the push for
            // 자동's reason — the preview is already showing the pending style on top.
            Tool.Style -> style.apply(state.document)?.let { styled ->
                sheetBaseline = null
                style.close()
                _uiState.value = _uiState.value.copy(selectedTool = null)
                history?.push(styled)
            }
            // specs/vibe_edit.md §3: 적용 runs the plan; the sheet closes when the run ends.
            Tool.Direct -> direct.apply()
            else -> {
                val stack = history
                if (state.selectedTool == Tool.Crop && stack != null) {
                    stack.push(state.cropState.applyTo(stack.current.value))
                }
                stack?.commitCoalesce()
                sheetBaseline = null
                _uiState.value = state.copy(selectedTool = null)
            }
        }
    }

    /**
     * specs/selection_tool.md §6: the mask becomes a file and one history entry. Writing it
     * first means a failed write leaves the sheet open with the selection intact, rather than
     * a document pointing at a file that is not there.
     */
    /**
     * specs/selection_tool.md §8.2: `cutOut = true` applies the mask *and* the cut-out as one
     * history entry. Public rather than wrapped in an `applyCutOut()`, because that wrapper was
     * one line hiding a default argument and `EditorViewModel` is at detekt's function ceiling.
     */
    fun applySelection(cutOut: Boolean = false) {
        val stack = history ?: return
        val mask = _uiState.value.selection.mask ?: return
        val maskId = newId()
        viewModelScope.launch {
            when (val saved = repository.saveMask(projectId, maskId, mask)) {
                is Result.Success -> {
                    val applied = stack.current.value.withMask(saved.value, maskId)
                    stack.push(if (cutOut) applied.withCutOut(maskId) else applied)
                    stack.commitCoalesce()
                    sheetBaseline = null
                    selection.closeSheet()
                    _uiState.value = _uiState.value.copy(selectedTool = null)
                }
                is Result.Failure -> selection.showMessage(R.string.select_failed)
            }
        }
    }

    fun reset() = history?.resetToOriginal() ?: Unit

    /**
     * specs/selection_tool.md §4: while the select sheet is open the top-bar Undo drives the
     * tool's own points, not the document. Points are not operations until Apply.
     */
    fun undo() {
        if (_uiState.value.selectedTool == Tool.Select) {
            selection.undoPoint()
        } else {
            history?.undo()
        }
    }

    fun redo() = history?.redo() ?: Unit

    /** specs/editor_shell.md: back autosaves; specs/persistence.md discards empty projects. */
    suspend fun onLeave() {
        // Before the save, so a slow write cannot hold the backend's session open (§6).
        selection.release()
        val document = _uiState.value.document ?: return
        if (!autosave.discardIfUntouched(document)) autosave.saveNow(document)
    }

    fun onStop() {
        viewModelScope.launch { _uiState.value.document?.let { autosave.saveNow(it) } }
    }

    companion object {
        /** specs/auto_enhance.md §6 and generative_fill.md §6: the tools that ask for pixels. */
        private val GENERATIVE_TOOLS = setOf(Tool.Erase, Tool.Fill, Tool.Expand, Tool.Auto)

        const val PROJECT_ID = "projectId"
        const val PREVIEW_LONG_EDGE_PX = 1080
    }
}

/**
 * T23: the crop geometry is normalised, so it needs the source's pixel aspect to hold a preset.
 * The bare-source preview has the source's shape. specs/vibe_edit.md §4.1's crop step needs the
 * same number, which is why this is shared rather than computed twice.
 *
 * File-level rather than a member: `EditorViewModel` is at detekt's function ceiling, and this
 * reads only its argument.
 */
private fun sourceAspect(state: EditorUiState): Float {
    val source = state.source
    return if (source == null || source.height <= 0) {
        1f
    } else {
        source.width.toFloat() / source.height
    }
}
