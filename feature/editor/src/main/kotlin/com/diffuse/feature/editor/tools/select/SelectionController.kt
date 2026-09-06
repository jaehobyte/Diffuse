package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * specs/selection_tool.md. The "선택" tool in one object: availability, the session, the points
 * and the mask. It is deliberately not part of `EditorViewModel` — the tool has its own
 * lifecycle (a session outlives the sheet) and its own undo stack, and mixing the two made both
 * harder to read.
 *
 * Nothing here touches the document. Committing the mask is `EditorViewModel`'s job, because
 * only it owns the history stack.
 */
class SelectionController(
    private val segmentation: SegmentationProvider,
    private val settings: Sam3Settings,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    /** In flight `open` or `byPoints`; a new prompt supersedes it rather than queueing (§4). */
    private var job: Job? = null

    init {
        scope.launch {
            segmentation.availability.collect { availability ->
                _state.value = _state.value.copy(availability = availability)
            }
        }
        scope.launch {
            settings.config.collect { config ->
                _state.value = _state.value.copy(config = config)
                segmentation.refresh()
            }
        }
    }

    /**
     * specs/selection_tool.md §1: a greyed tool still explains itself. When the reason is that
     * nothing is configured, the settings sheet is more use than a snackbar saying so.
     *
     * @return true when the sheet may open.
     */
    fun onToolTapped(): Boolean {
        val availability = _state.value.availability
        if (availability is Availability.Ready) return true
        val reason = (availability as? Availability.Unavailable)?.reason
        _state.value = if (reason is AppError.Invalid) {
            _state.value.copy(showSettings = true)
        } else {
            _state.value.copy(
                message = when (reason) {
                    AppError.Unauthorized -> R.string.select_unavailable_unauthorized
                    else -> R.string.select_unavailable_offline
                },
            )
        }
        return false
    }

    /**
     * specs/selection_tool.md §9: the session is opened on the **current preview**, not the
     * source, so the mask lines up with what the user is looking at. Re-opening the sheet in the
     * same editor session reuses it.
     */
    fun open(preview: Bitmap) {
        if (_state.value.session != null) return
        job?.cancel()
        _state.value = _state.value.copy(preparing = true)
        job = scope.launch {
            _state.value = when (val opened = segmentation.open(preview)) {
                is Result.Success -> _state.value.copy(session = opened.value, preparing = false)
                is Result.Failure ->
                    _state.value.copy(preparing = false, message = R.string.select_failed)
            }
        }
    }

    /** specs/selection_tool.md §2: tap adds a foreground point, long-press a background one. */
    fun addPoint(x: Float, y: Float, foreground: Boolean) {
        val current = _state.value
        if (current.session == null) return
        segment(current.withPoint(PointF(x, y), foreground))
    }

    /** specs/selection_tool.md §4: undo drops the last point and re-segments what is left. */
    fun undoPoint() {
        val current = _state.value
        if (current.points.isEmpty()) return
        segment(current.withoutLastPoint())
    }

    fun invert() {
        val current = _state.value
        val mask = current.mask ?: return
        _state.value = current.copy(mask = MaskOps.inverted(mask), inverted = !current.inverted)
    }

    /** 지우기: the selection goes, the session stays. */
    fun clear() {
        job?.cancel()
        _state.value = _state.value.cleared()
    }

    /** DESIGN.md §7: AI work always offers a way out. */
    fun cancelWork() {
        job?.cancel()
        _state.value = _state.value.copy(preparing = false, busy = false)
    }

    /** Cancel or Apply closed the sheet. The session survives so re-opening is instant (§6). */
    fun closeSheet() {
        job?.cancel()
        _state.value = _state.value.cleared().copy(preparing = false, busy = false)
    }

    fun saveSettings(baseUrl: String, token: String) {
        settings.update(baseUrl, token)
        _state.value = _state.value.copy(showSettings = false)
    }

    fun dismissSettings() {
        _state.value = _state.value.copy(showSettings = false)
    }

    fun showMessage(res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Only the latest prompt matters: an in-flight call is cancelled rather than queued, and the
     * previous mask stays on screen until the new one lands (specs/selection_tool.md §5).
     */
    private fun segment(next: SelectionState) {
        val session = next.session ?: return
        val prompt = next.prompt
        job?.cancel()
        if (prompt == null) {
            _state.value = next.cleared()
            return
        }
        _state.value = next.copy(busy = true)
        job = scope.launch {
            _state.value = when (val result = segmentation.byPoints(session, prompt)) {
                is Result.Success -> {
                    val alpha = if (next.inverted) {
                        MaskOps.inverted(result.value.alpha)
                    } else {
                        result.value.alpha
                    }
                    next.copy(
                        mask = alpha.takeUnless(MaskOutline::isEmpty),
                        lowConfidence = result.value.score < SelectionState.LOW_CONFIDENCE_SCORE,
                        busy = false,
                    )
                }
                // The mask the user already had survives a failed prompt.
                is Result.Failure -> next.copy(busy = false, message = R.string.select_failed)
            }
        }
    }
}
