package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.PromptSuggestionProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** specs/vibe_edit.md §14. Where the suggestion area is; [Failed] has already shown its snackbar. */
enum class SuggestionPhase { Idle, Loading, Tailored, Empty, Failed }

/** specs/vibe_edit.md §14. What the area under the prompt bar shows. */
data class SuggestionState(
    val phase: SuggestionPhase = SuggestionPhase.Idle,
    /** The tailored ids, only meaningful in [SuggestionPhase.Tailored]. */
    val ids: List<PromptSuggestionId> = emptyList(),
    /** A preview rendered from the document on screen exists, so a request may be sent. */
    val frameReady: Boolean = false,
    /** 숨기기, for the rest of this sheet session. */
    val hidden: Boolean = false,
)

/** Who wrote the sentence in the bar: an unedited suggestion can be swapped, a typed one cannot. */
enum class RequestSource { Typed, Suggestion }

/** §14: the general examples, shown before any analysis and never labelled as tailored. */
val GeneralSuggestions = listOf(
    PromptSuggestionId.Brighten,
    PromptSuggestionId.Warm,
    PromptSuggestionId.NaturalColor,
)

@StringRes
fun PromptSuggestionId.labelRes(): Int = when (this) {
    PromptSuggestionId.Brighten -> R.string.direct_suggest_brighten_label
    PromptSuggestionId.LiftShadows -> R.string.direct_suggest_lift_shadows_label
    PromptSuggestionId.SoftenHighlights -> R.string.direct_suggest_soften_highlights_label
    PromptSuggestionId.Warm -> R.string.direct_suggest_warm_label
    PromptSuggestionId.Cool -> R.string.direct_suggest_cool_label
    PromptSuggestionId.NaturalColor -> R.string.direct_suggest_natural_color_label
    PromptSuggestionId.VividColor -> R.string.direct_suggest_vivid_color_label
    PromptSuggestionId.FilmWarm -> R.string.direct_suggest_film_warm_label
}

@StringRes
fun PromptSuggestionId.requestRes(): Int = when (this) {
    PromptSuggestionId.Brighten -> R.string.direct_suggest_brighten_request
    PromptSuggestionId.LiftShadows -> R.string.direct_suggest_lift_shadows_request
    PromptSuggestionId.SoftenHighlights -> R.string.direct_suggest_soften_highlights_request
    PromptSuggestionId.Warm -> R.string.direct_suggest_warm_request
    PromptSuggestionId.Cool -> R.string.direct_suggest_cool_request
    PromptSuggestionId.NaturalColor -> R.string.direct_suggest_natural_color_request
    PromptSuggestionId.VividColor -> R.string.direct_suggest_vivid_color_request
    PromptSuggestionId.FilmWarm -> R.string.direct_suggest_film_warm_request
}

@StringRes
fun PromptSuggestionId.hintRes(): Int = when (this) {
    PromptSuggestionId.Brighten -> R.string.direct_suggest_brighten_hint
    PromptSuggestionId.LiftShadows -> R.string.direct_suggest_lift_shadows_hint
    PromptSuggestionId.SoftenHighlights -> R.string.direct_suggest_soften_highlights_hint
    PromptSuggestionId.Warm -> R.string.direct_suggest_warm_hint
    PromptSuggestionId.Cool -> R.string.direct_suggest_cool_hint
    PromptSuggestionId.NaturalColor -> R.string.direct_suggest_natural_color_hint
    PromptSuggestionId.VividColor -> R.string.direct_suggest_vivid_color_hint
    PromptSuggestionId.FilmWarm -> R.string.direct_suggest_film_warm_hint
}

/**
 * specs/vibe_edit.md §14. The read-only suggestion request, owned by [DirectController]. It sends
 * the frame only on [find], at most once per tap, and keeps the last answer for the document it
 * was asked about. A request is identified by the document it was rendered from and a generation
 * that every invalidation bumps, so an answer for a picture that is gone is dropped even when the
 * provider ignored its cancellation.
 */
class DirectSuggestions internal constructor(
    private val provider: PromptSuggestionProvider,
    private val scope: CoroutineScope,
    settingsChanges: Flow<Any>,
    private val onFailure: (AppError) -> Unit,
) {

    private val _state = MutableStateFlow(SuggestionState())
    val state: StateFlow<SuggestionState> = _state.asStateFlow()

    private var document: EditDocument? = null
    private var frame: Bitmap? = null
    private var cached: Pair<EditDocument, List<PromptSuggestionId>>? = null
    private var job: Job? = null
    private var generation = 0

    init {
        // §14: a new key or model is a new answer, and one only the user may ask for.
        scope.launch { settingsChanges.drop(1).collect { invalidate() } }
    }

    /**
     * The document on screen and, when a preview rendered from exactly that document exists, the
     * preview. A different document drops the answer and any request for the old one.
     */
    fun onCanvas(document: EditDocument?, preview: Bitmap?) {
        if (document != this.document) {
            this.document = document
            invalidate()
        }
        frame = preview
        _state.value = _state.value.copy(frameReady = preview != null)
    }

    /** 사진에 맞는 문장 보기 / 다시 찾기: one call per tap, none while one is running or answered. */
    fun find() {
        val document = document
        val preview = frame
        val cachedIds = cached?.takeIf { it.first == document }?.second
        when {
            document == null || preview == null || _state.value.phase == SuggestionPhase.Loading -> Unit
            cachedIds != null -> _state.value = _state.value.copy(phase = phaseOf(cachedIds), ids = cachedIds)
            else -> request(document, preview)
        }
    }

    private fun request(document: EditDocument, preview: Bitmap) {
        val asked = ++generation
        _state.value = _state.value.copy(phase = SuggestionPhase.Loading, ids = emptyList())
        job = scope.launch {
            val result = provider.suggest(preview)
            if (asked != generation) return@launch
            _state.value = when (result) {
                is Result.Success -> {
                    cached = document to result.value
                    _state.value.copy(phase = phaseOf(result.value), ids = result.value)
                }
                is Result.Failure -> {
                    onFailure(result.error)
                    _state.value.copy(phase = SuggestionPhase.Failed, ids = emptyList())
                }
            }
        }
    }

    /** The area's 취소, a submit, or the end of the session: a request in flight is dropped. */
    fun cancel() {
        if (_state.value.phase != SuggestionPhase.Loading) return
        stop()
        _state.value = _state.value.copy(phase = SuggestionPhase.Idle)
    }

    fun hide() {
        _state.value = _state.value.copy(hidden = true)
    }

    /** The sheet closed: the next session starts unhidden, with any finished answer kept. */
    fun endSession() {
        cancel()
        val phase = _state.value.phase.takeUnless { it == SuggestionPhase.Failed } ?: SuggestionPhase.Idle
        _state.value = _state.value.copy(hidden = false, phase = phase)
    }

    private fun invalidate() {
        stop()
        cached = null
        _state.value = _state.value.copy(phase = SuggestionPhase.Idle, ids = emptyList())
    }

    private fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    private fun phaseOf(ids: List<PromptSuggestionId>): SuggestionPhase =
        if (ids.isEmpty()) SuggestionPhase.Empty else SuggestionPhase.Tailored
}
