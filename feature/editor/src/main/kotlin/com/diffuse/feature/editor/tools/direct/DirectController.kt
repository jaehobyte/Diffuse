package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.AppError
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * specs/vibe_edit.md §10. What tapping the tool should do, returned rather than acted on: the
 * 서버 설정 sheet is `SelectionController`'s, and there is one of it (generative_erase.md §9).
 */
enum class DirectTap {
    Open,

    /** The key is blank, which only the settings sheet can fix. */
    OpenSettings,
}

/** A snackbar line. [arg] is the phrase for `direct_not_found`, the one message that names one. */
data class DirectMessage(@StringRes val res: Int, val arg: String? = null)

/**
 * What the canvas is showing, at the moment a plan is asked for or run. Supplied by
 * `EditorViewModel`, which is the only object that knows it; null while the document or its
 * preview is still loading.
 */
data class DirectCanvas(
    val document: EditDocument,
    val preview: Bitmap,
    val activeMask: Bitmap?,
    /** Width ÷ height of the un-cropped source, which a `Crop` step needs (§4.1). */
    val sourceAspect: Float,
)

/**
 * The four things the tool cannot know for itself: what the canvas is showing, where history
 * lives, that a run invalidates the 선택 tool's session (§9.4), and that the sheet closes when
 * the run ends (§3). `EditorViewModel` is the one object that knows all four.
 */
interface DirectHost {

    fun canvas(): DirectCanvas?

    /** §9.3: one history entry per committed step, no coalesce key. */
    fun commit(document: EditDocument)

    suspend fun releaseSession()

    /**
     * §3: the sheet closes when the run ends. [cropRatio] is §4.1's hand-off — non-null when the
     * plan ended with a `Crop` and every step committed, so the 자르기 tool opens on the rect that
     * was just applied, with that ratio still locked, and the user chooses the framing. The
     * controller reports what ran; opening a tool is the ViewModel's, as the one object that can.
     */
    fun onFinished(cropRatio: CropRatio?)
}

/** specs/vibe_edit.md §3, §14. Sheet state; nothing here reaches the document until 적용. */
data class DirectState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val request: String = "",
    /** §14: an unedited example may be swapped for another; a typed sentence is the user's. */
    val requestSource: RequestSource = RequestSource.Typed,
    /** §14: the request in flight or answered — the proposal, its outcome and its mode. */
    val proposals: ProposalState = ProposalState(),
    val running: Boolean = false,
    val message: DirectMessage? = null,
    val suggestions: SuggestionState = SuggestionState(),
) {

    val enabled: Boolean get() = availability is Availability.Ready

    val phase: ProposalPhase get() = proposals.phase

    val proposal: PlanProposal? get() = proposals.proposal

    /** DESIGN.md §7: a sent 지시 plans behind the overlay. */
    val planning: Boolean get() = phase == ProposalPhase.Loading && proposals.mode == PlanMode.Direct

    /** §14: 보정 추천 runs inline, with its own 취소, and keeps the bar usable. */
    val recommending: Boolean get() = phase == ProposalPhase.Loading && proposals.mode == PlanMode.Recommend

    /** DESIGN.md §7: both the planning call and the run show progress and a way out. */
    val working: Boolean get() = planning || running

    /** The plan waiting for 적용 — only for a ready proposal. It is never persisted (§13). */
    val plan: EditPlan? get() = proposal?.takeIf { phase == ProposalPhase.Ready }?.plan

    val canApply: Boolean get() = plan != null && !working

    /** §10: a refused answer is a hint under the bar, never a snackbar and never a dialog. */
    val notUnderstood: Boolean get() = phase == ProposalPhase.NotUnderstood

    /** §14: the 보정 추천 entry needs a frame of the document on screen and nothing else running. */
    val canRecommend: Boolean get() = enabled && proposals.frameReady && !working && !recommending

    /**
     * §14: the example sentences, only over an empty bar or an unedited example, so they can
     * never overwrite what the user wrote, and never while a run is changing the photo. A
     * proposal under review takes their place, so its sections stay inside the 45% sheet.
     */
    val showExamples: Boolean
        get() = !suggestions.hidden && !running && proposal == null &&
            (request.isEmpty() || requestSource == RequestSource.Suggestion)
}

/**
 * specs/vibe_edit.md §3, §10, §14. Owns the proposal between the response and 적용. Running it is
 * `EditorViewModel`'s job, because only it holds the `HistoryStack` — the split the selection
 * and erase tools already use.
 */
class DirectController(
    provider: EditPlanProvider,
    private val runner: PlanRunner,
    private val scope: CoroutineScope,
    private val host: DirectHost,
    /** §14: the Gemini settings; any change drops the proposal asked under the old ones. */
    settingsChanges: Flow<Any>,
) {

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    private var run: Job? = null

    /** specs/vibe_edit.md §14: the one planning request, for 보정 추천 and a sent 지시 alike. */
    val proposals = DirectProposals(provider, runner, scope, settingsChanges, { _state.value.running }) { error, mode ->
        showMessage(
            when {
                error is AppError.Unauthorized -> R.string.direct_needs_key
                error is AppError.Invalid && error.detail.startsWith(BLOCKED_PREFIX) -> R.string.direct_blocked
                mode == PlanMode.Recommend -> R.string.direct_recommend_failed
                else -> R.string.direct_failed
            },
        )
    }

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
        }
        scope.launch {
            proposals.state.collect { _state.value = _state.value.copy(proposals = it) }
        }
    }

    /** §10: a blank key greys the tool and opens the 서버 설정 sheet rather than a snackbar. */
    fun onToolTapped(): DirectTap =
        if (_state.value.enabled) {
            DirectTap.Open
        } else {
            showMessage(R.string.direct_needs_key)
            DirectTap.OpenSettings
        }

    /** §14: what the canvas is showing, with a frame only when it was rendered from it. */
    fun onCanvas(document: EditDocument?, preview: Bitmap?) {
        proposals.onCanvas(document, preview)
    }

    /**
     * §14: a different sentence invalidates the proposal waiting for 적용, and cancels one still
     * being asked for — it would answer words that are no longer in the bar.
     */
    fun setRequest(request: String) {
        replaceRequest(request, RequestSource.Typed)
    }

    /** §14: an example fills the bar and nothing else — no plan, no edit, no history. */
    fun pickSuggestion(request: String) {
        replaceRequest(request, RequestSource.Suggestion)
    }

    private fun replaceRequest(request: String, source: RequestSource) {
        val state = _state.value
        if (state.running || (request == state.request && source == state.requestSource)) return
        if (request != state.request) proposals.invalidate()
        _state.value = _state.value.copy(request = request, requestSource = source)
    }

    /**
     * §14 보정 추천 받기 / 다시 추천: the current photo and whatever the bar holds — empty is fine.
     * One call per tap; [again] asks anew even for an identical snapshot.
     */
    fun recommend(again: Boolean = false) {
        if (!_state.value.canRecommend) return
        _state.value = _state.value.copy(message = null)
        proposals.ask(_state.value.request, PlanMode.Recommend, force = again)
    }

    /**
     * §3: submitting asks for a plan, and a second phrase replaces the first — nothing has been
     * applied yet, so there is nothing to undo. A recommendation still being asked is dropped.
     */
    fun submit(request: String) {
        val state = _state.value
        if (request.isBlank() || state.running || !state.enabled) return
        _state.value = state.copy(request = request, message = null)
        proposals.ask(request, PlanMode.Direct, force = true)
    }

    /** §14: the inline 취소 of a recommendation; the bar and the document stay as they are. */
    fun cancelRecommendation() {
        if (_state.value.recommending) proposals.cancel()
    }

    /** §14: 숨기기 hides the examples for the session; 보정 추천 stays reachable. */
    fun hideExamples() {
        _state.value = _state.value.copy(suggestions = _state.value.suggestions.copy(hidden = true))
    }

    /**
     * §9.2, §9.3, §14: 적용 runs the reviewed plan — never a new one — one history entry per step
     * and no coalesce key, and only while the document, the text and the settings it was asked
     * from still hold. The sheet closes when the run ends.
     */
    fun apply() {
        val state = _state.value
        val canvas = host.canvas()
        if (!state.canApply || canvas == null) return
        val proposal = proposals.readyFor(canvas.document, state.request)
        if (proposal == null) {
            proposals.invalidate()
            showMessage(R.string.direct_stale)
            return
        }
        val plan = proposal.plan
        _state.value = state.copy(running = true, message = null)
        run = scope.launch {
            if (plan.steps.any { it is PlanStep.Select }) host.releaseSession()
            var error: AppError? = null
            runner.run(
                plan = plan,
                document = canvas.document,
                preview = canvas.preview,
                activeMask = canvas.activeMask,
                sourceAspect = canvas.sourceAspect,
            ).collect { event ->
                when (event) {
                    is RunEvent.Committed -> host.commit(event.document)
                    is RunEvent.Stopped -> error = event.error
                    is RunEvent.Started, RunEvent.Completed -> Unit
                }
            }
            onRunEnded(error)
            // §4.1: only a run that finished actually committed the crop. A run that stopped
            // early may never have reached it, and opening the tool then would be a lie.
            host.onFinished(
                cropRatio = (plan.steps.lastOrNull() as? PlanStep.Crop)?.ratio.takeIf { error == null },
            )
        }
    }

    /** §9.3: the run ended, whichever way. The proposal dies with the sheet either way (§13). */
    private fun onRunEnded(error: AppError?) {
        _state.value = _state.value.copy(
            running = false,
            request = "",
            requestSource = RequestSource.Typed,
            message = error?.let(::messageOf),
        )
        proposals.invalidate()
    }

    /** 취소, system back or another tool: the proposal is discarded; nothing ran before 적용 (§3). */
    fun close() {
        if (_state.value.running) run?.cancel()
        proposals.invalidate()
        _state.value = _state.value.copy(
            request = "",
            requestSource = RequestSource.Typed,
            running = false,
            suggestions = _state.value.suggestions.copy(hidden = false),
        )
    }

    /**
     * DESIGN.md §7: the overlay's cancel button. A cancelled run keeps the steps it committed
     * (§9.3) and its proposal goes — the document is no longer the one it was asked about.
     */
    fun cancelWork() {
        val running = _state.value.running
        run?.cancel()
        if (running) proposals.invalidate() else proposals.cancel()
        _state.value = _state.value.copy(running = false)
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = DirectMessage(res))
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * §10's last three rows. A `Select` that found nothing names the word it failed on, which
     * is why `PlanRunner` puts the phrase in the detail rather than inventing an `AppError`.
     */
    private fun messageOf(error: AppError): DirectMessage {
        val detail = (error as? AppError.Invalid)?.detail
        return if (detail != null && detail.startsWith(PlanRunner.NOT_FOUND_PREFIX)) {
            DirectMessage(
                R.string.direct_not_found,
                detail.removePrefix(PlanRunner.NOT_FOUND_PREFIX),
            )
        } else {
            DirectMessage(messageFor(error))
        }
    }

    /** §6: a safety block is an `Invalid` with a recognizable prefix, not its own case. */
    @StringRes
    private fun messageFor(error: AppError): Int =
        if (error is AppError.Invalid && error.detail.startsWith(BLOCKED_PREFIX)) {
            R.string.direct_blocked
        } else {
            R.string.direct_failed
        }

    private companion object {
        /** Duplicated from `core:ai` for the reason `EraseController` gives: it is internal. */
        const val BLOCKED_PREFIX = "blocked:"
    }
}
