package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * specs/vibe_edit.md §14. Where a 보정 추천 or a sent 지시 is. [Loading] is inline for a
 * recommendation and the overlay for a 지시; [NotUnderstood] is an answer the app could not
 * accept (refused whole, or one `PlanRunner.validate` rejects) and is a hint, not a snackbar.
 */
enum class ProposalPhase {
    Idle,
    Loading,
    Ready,
    NoChange,
    NeedsClarification,
    Unsupported,
    NotUnderstood,
    Failed,
    Cancelled,
}

/** specs/vibe_edit.md §14. The sheet-session state of the area under the prompt bar. */
data class SuggestionState(
    /** A preview rendered from the document on screen exists, so a request may be sent. */
    val frameReady: Boolean = false,
    /** 숨기기: the example sentences, for the rest of this sheet session. */
    val hidden: Boolean = false,
)

/** Who wrote the sentence in the bar: an unedited example can be swapped, a typed one cannot. */
enum class RequestSource { Typed, Suggestion }

/**
 * §14: the general example sentences, shown with no claim of analysis. Picking one fills the bar
 * and nothing else — they are an input aid, and are not the photo-tailored 보정 추천.
 */
enum class ExampleSentence(val tag: String) {
    Brighten("brighten"),
    Warm("warm"),
    NaturalColor("natural_color"),
}

@StringRes
fun ExampleSentence.labelRes(): Int = when (this) {
    ExampleSentence.Brighten -> R.string.direct_suggest_brighten_label
    ExampleSentence.Warm -> R.string.direct_suggest_warm_label
    ExampleSentence.NaturalColor -> R.string.direct_suggest_natural_color_label
}

@StringRes
fun ExampleSentence.requestRes(): Int = when (this) {
    ExampleSentence.Brighten -> R.string.direct_suggest_brighten_request
    ExampleSentence.Warm -> R.string.direct_suggest_warm_request
    ExampleSentence.NaturalColor -> R.string.direct_suggest_natural_color_request
}

@StringRes
fun ExampleSentence.hintRes(): Int = when (this) {
    ExampleSentence.Brighten -> R.string.direct_suggest_brighten_hint
    ExampleSentence.Warm -> R.string.direct_suggest_warm_hint
    ExampleSentence.NaturalColor -> R.string.direct_suggest_natural_color_hint
}

/**
 * §14: the document as the planner may know it — every adjustment as the final setting
 * `withAdjust` would replace, and whether a selection or crop exists. Pure, so `core:ai` never
 * reads an `EditDocument`.
 */
fun planContextOf(document: EditDocument): PlanContext = PlanContext(
    adjustments = document.operations.filterIsInstance<Operation.Adjust>().map {
        CurrentAdjust(it.kind, it.value, masked = it.maskId != null)
    },
    hasSelection = document.activeMaskId != null,
    cropped = document.crop() != null,
)

/** What a proposal was asked from. Two asks with equal snapshots would send the same request. */
private data class PlanSnapshot(
    /** The document the frame was rendered from; its `id` is the project. */
    val document: EditDocument,
    val text: String,
    val mode: PlanMode,
    val context: PlanContext,
    /** Bumped by every Gemini settings change. */
    val settings: Int,
)

/** specs/vibe_edit.md §14. The asking half of [DirectState]. */
data class ProposalState(
    val phase: ProposalPhase = ProposalPhase.Idle,
    val mode: PlanMode = PlanMode.Direct,
    /** The answered proposal under review, whatever its outcome. Never persisted. */
    val proposal: PlanProposal? = null,
    val frameReady: Boolean = false,
)

/**
 * specs/vibe_edit.md §14. Owns one planning request at a time, for 보정 추천 (inline) and a sent
 * 지시 (the overlay) alike. A request is pinned to a [PlanSnapshot] — the document the frame was
 * rendered from, the text verbatim, the mode, the settings context and the Gemini settings
 * generation — and a sequence number every invalidation bumps, so an answer for anything that
 * has since changed is dropped even when the provider ignored its cancellation.
 *
 * It never edits and never plans on its own: only [ask] sends, and only with a frame of the
 * document on screen. A 지시 sent before that frame exists waits for it.
 */
class DirectProposals internal constructor(
    private val provider: EditPlanProvider,
    private val runner: PlanRunner,
    private val scope: CoroutineScope,
    settingsChanges: Flow<Any>,
    /** True while a run commits; its commits change the document and must not invalidate it. */
    private val running: () -> Boolean,
    private val onFailure: (AppError, PlanMode) -> Unit,
) {

    private val _state = MutableStateFlow(ProposalState())
    val state: StateFlow<ProposalState> = _state.asStateFlow()

    private var document: EditDocument? = null
    private var frame: Bitmap? = null
    private var waiting: Pair<String, PlanMode>? = null
    private var asked: PlanSnapshot? = null
    private var cached: Pair<PlanSnapshot, PlanProposal>? = null
    private var settings = 0
    private var seq = 0
    private var job: Job? = null

    init {
        // §14: a new key or model is a new answer, and one only the user may ask for.
        scope.launch {
            settingsChanges.drop(1).collect {
                settings++
                invalidate()
            }
        }
    }

    /**
     * The document on screen and, when a preview rendered from exactly that document exists, the
     * preview. A different document drops the proposal and any request for the old one.
     */
    fun onCanvas(document: EditDocument?, preview: Bitmap?) {
        if (document != this.document) {
            this.document = document
            if (!running()) invalidate()
        }
        frame = preview
        _state.value = _state.value.copy(frameReady = preview != null)
        val pending = waiting
        if (pending != null && document != null && preview != null) {
            waiting = null
            send(pending.first, pending.second, document, preview)
        }
    }

    /**
     * One request per call. [force] is 다시 추천: without it, an answer for an identical snapshot
     * is shown again rather than asked for twice.
     */
    fun ask(text: String, mode: PlanMode, force: Boolean = false) {
        val document = document
        val preview = frame
        if (document == null || preview == null) {
            if (mode == PlanMode.Direct) {
                stop()
                waiting = text to mode
                _state.value = _state.value.copy(phase = ProposalPhase.Loading, mode = mode, proposal = null)
            }
            return
        }
        val hit = cached?.takeIf { !force && it.first == snapshotOf(text, mode, document) }
        if (hit != null) {
            stop()
            asked = hit.first
            show(hit.second, hit.first)
        } else {
            send(text, mode, document, preview)
        }
    }

    private fun send(text: String, mode: PlanMode, document: EditDocument, preview: Bitmap) {
        stop()
        val snapshot = snapshotOf(text, mode, document)
        val mine = seq
        asked = snapshot
        _state.value = _state.value.copy(phase = ProposalPhase.Loading, mode = mode, proposal = null)
        job = scope.launch {
            val result = provider.plan(preview, PlanRequest(text, mode, snapshot.context))
            if (mine != seq) return@launch
            when (result) {
                is Result.Success -> {
                    cached = snapshot to result.value
                    show(result.value, snapshot)
                }
                is Result.Failure -> fail(result.error, mode)
            }
        }
    }

    /** §9.1 still applies on arrival: a ready plan the runner would refuse is not shown as ready. */
    private fun show(proposal: PlanProposal, snapshot: PlanSnapshot) {
        val phase = when (proposal.outcome) {
            PlanOutcome.Ready -> if (
                proposal.workflow.isNotEmpty() && runner.validate(proposal.plan, snapshot.document) == null
            ) {
                ProposalPhase.Ready
            } else {
                ProposalPhase.NotUnderstood
            }
            PlanOutcome.NoChange -> ProposalPhase.NoChange
            PlanOutcome.NeedsClarification -> ProposalPhase.NeedsClarification
            PlanOutcome.Unsupported -> ProposalPhase.Unsupported
        }
        _state.value = _state.value.copy(
            phase = phase,
            mode = snapshot.mode,
            proposal = proposal.takeUnless { phase == ProposalPhase.NotUnderstood },
        )
    }

    /** §14: a refused envelope is the hint; anything else is a failure the user can retry. */
    private fun fail(error: AppError, mode: PlanMode) {
        val refused = error is AppError.Invalid && error.detail.startsWith(UNPLANNABLE_PREFIX)
        _state.value = _state.value.copy(
            phase = if (refused) ProposalPhase.NotUnderstood else ProposalPhase.Failed,
            proposal = null,
        )
        if (!refused) onFailure(error, mode)
    }

    /**
     * The ready proposal, but only while everything it was asked from still holds: the same
     * document, the same text and the same settings. Checked again at 적용.
     */
    fun readyFor(document: EditDocument, text: String): PlanProposal? {
        val snapshot = asked ?: return null
        val current = _state.value
        val same = snapshot.document == document && snapshot.text == text && snapshot.settings == settings
        return current.proposal.takeIf { current.phase == ProposalPhase.Ready && same }
    }

    /** 취소, inline or on the overlay: the request is dropped and the bar keeps its text. */
    fun cancel() {
        if (_state.value.phase != ProposalPhase.Loading) return
        stop()
        cached = null
        asked = null
        _state.value = _state.value.copy(phase = ProposalPhase.Cancelled, proposal = null)
    }

    /** Any change to what a proposal was asked from: the answer, the cache and the request go. */
    fun invalidate() {
        stop()
        cached = null
        asked = null
        _state.value = _state.value.copy(phase = ProposalPhase.Idle, proposal = null)
    }

    private fun stop() {
        seq++
        waiting = null
        job?.cancel()
        job = null
    }

    private fun snapshotOf(text: String, mode: PlanMode, document: EditDocument) =
        PlanSnapshot(document, text, mode, planContextOf(document), settings)

    private companion object {
        /** Duplicated from `core:ai` for the reason `BLOCKED_PREFIX` is: it is internal there. */
        const val UNPLANNABLE_PREFIX = "unplannable:"
    }
}
