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
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.tools.crop.CropState
import com.diffuse.feature.editor.tools.direct.DirectCanvas
import com.diffuse.feature.editor.tools.direct.DirectController
import com.diffuse.feature.editor.tools.direct.DirectHost
import com.diffuse.feature.editor.tools.direct.DirectState
import com.diffuse.feature.editor.tools.direct.DirectTap
import com.diffuse.feature.editor.tools.direct.PlanRunner
import com.diffuse.feature.editor.tools.erase.EraseCommit
import com.diffuse.feature.editor.tools.erase.EraseController
import com.diffuse.feature.editor.tools.erase.eraseInput
import com.diffuse.feature.editor.tools.erase.EraseState
import com.diffuse.feature.editor.tools.erase.EraseTap
import com.diffuse.feature.editor.tools.select.SelectionController
import com.diffuse.feature.editor.tools.select.SelectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
            dispatchers = dispatchers,
            saveMask = { maskId, mask -> repository.saveMask(projectId, maskId, mask) },
            eraseCommit = eraseCommit,
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
                    DirectCanvas(document, preview, state.activeMask)
                }
            }

            override fun commit(document: EditDocument) {
                history?.push(document)
            }

            override suspend fun releaseSession() = selection.release()

            override fun onFinished() {
                sheetBaseline = null
                _uiState.value = _uiState.value.copy(selectedTool = null)
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
     */
    private fun requestPreview(document: EditDocument) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val rendered = renderer.preview(document, PREVIEW_LONG_EDGE_PX)
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
            tool == Tool.Erase -> onEraseTapped(state)
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
                // T23: the crop geometry is normalised, so it needs the source's pixel aspect
                // to hold a preset. The bare-source preview has the source's shape.
                val source = state.source
                val aspect = if (source == null || source.height <= 0) {
                    1f
                } else {
                    source.width.toFloat() / source.height
                }
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
            }
        }
    }


    /**
     * specs/generative_erase.md §5, §9: the tool has no sheet — tapping it either runs the model
     * or says which of the four things is missing.
     */
    private fun onEraseTapped(state: EditorUiState) {
        when (erase.onToolTapped(hasSelection = state.document?.activeMaskId != null)) {
            EraseTap.Refused -> Unit
            EraseTap.OpenSettings -> selection.setSettingsVisible(true)
            // The eraser is shown the frame without the adjustments; see [eraseInput].
            EraseTap.Run -> viewModelScope.launch {
                val document = state.document
                erase.runAndCommit(
                    image = document?.let { eraseInput(renderer, it, PREVIEW_LONG_EDGE_PX) }
                        ?: state.preview?.asAndroidBitmap(),
                    mask = state.activeMask,
                    document = document,
                    onCommitted = { committed -> history?.push(committed) },
                )
            }
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
        direct.close()
        _uiState.value = _uiState.value.copy(selectedTool = null)
    }

    fun applySheet() {
        val state = _uiState.value
        when (state.selectedTool) {
            Tool.Select -> applySelection()
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
    /** specs/selection_tool.md §8.2: applies the mask *and* the cut-out as one history entry. */
    fun applyCutOut() = applySelection(cutOut = true)

    private fun applySelection(cutOut: Boolean = false) {
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
        const val PROJECT_ID = "projectId"
        const val PREVIEW_LONG_EDGE_PX = 1080
    }
}
