package com.diffuse.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.data.ProjectAutosave
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.tools.crop.CropState
import com.diffuse.feature.editor.tools.erase.EraseController
import com.diffuse.feature.editor.tools.erase.EraseState
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
    val selection = SelectionController(ai.segmentation, ai.sam3Settings, viewModelScope)

    /** specs/prompt_input.md §3: handed straight to the prompt bar; the VM never drives it. */
    val speech: SpeechInput = ai.speech

    /** specs/generative_erase.md: runs the model; this class is what commits the result. */
    val erase = EraseController(ai.erase, viewModelScope)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            selection.state.collect { _uiState.value = _uiState.value.copy(selection = it) }
        }
        viewModelScope.launch {
            erase.state.collect { _uiState.value = _uiState.value.copy(erase = it) }
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
        if (state.selectedTool == tool) {
            cancelSheet()
        } else if (tool == Tool.Select && !selection.onToolTapped()) {
            return
        } else if (tool == Tool.Erase) {
            // specs/generative_erase.md §5: no sheet — tapping the tool runs it.
            erase.runAndCommit(
                image = state.preview?.asAndroidBitmap(),
                mask = state.activeMask,
                maskId = state.document?.activeMaskId,
                save = { eraseId, result -> repository.saveEraseResult(projectId, eraseId, result) },
                commit = { maskId, ref, eraseId ->
                    history?.run {
                        push(current.value.withGenerativeErase(maskId, ref, eraseId))
                        commitCoalesce()
                    }
                },
            )
        } else {
            sheetBaseline = state.document
            // T23: the crop geometry is normalised, so it needs the source's pixel aspect to
            // hold a preset. The bare-source preview has the source's shape.
            val aspect = state.source
                ?.takeIf { it.height > 0 }
                ?.let { it.width.toFloat() / it.height }
                ?: 1f
            _uiState.value = state.copy(
                selectedTool = tool,
                cropState = state.document?.let { CropState.from(it, aspect) } ?: CropState(),
            )
            if (tool == Tool.Select) state.preview?.let { selection.open(it.asAndroidBitmap()) }
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
        _uiState.value = _uiState.value.copy(selectedTool = null)
    }

    fun applySheet() {
        val stack = history
        val state = _uiState.value
        if (state.selectedTool == Tool.Select) {
            applySelection()
            return
        }
        if (state.selectedTool == Tool.Crop && stack != null) {
            stack.push(state.cropState.applyTo(stack.current.value))
        }
        stack?.commitCoalesce()
        sheetBaseline = null
        _uiState.value = state.copy(selectedTool = null)
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
