package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import com.diffuse.core.ai.CropNote
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.OperationNote
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanTargetState
import com.diffuse.core.ai.StyleEffect
import com.diffuse.core.ai.StyleId
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.ai.TopazCapability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.style.StylePreset
import com.diffuse.core.imaging.style.atIntensity
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * §14, §15: the document as the planner may know it — every adjustment as the final setting
 * `withAdjust` would replace, every selection by an opaque ref with what this session confirmed
 * it to be, and every operation in render order. Pure, so `core:ai` never reads an
 * `EditDocument`. Refs are positional (`t1`, `a1` …) and only mean something inside the snapshot;
 * the mask ids they resolve to stay here.
 *
 * @param notes mask id → the phrase a plan's selection was found for in this session (§19: found
 * by the segmenter and used without asking, so [TargetSource.Resolved], never "confirmed by the
 * user"). A selection with no note is unknown: a legacy, manual or earlier-session one is never
 * guessed.
 */
// The snapshot is one fact per argument; grouping them would only rename the list.
@Suppress("LongParameterList")
fun planContextOf(
    document: EditDocument,
    notes: Map<String, String> = emptyMap(),
    styles: List<StyleEffect> = emptyList(),
    topaz: TopazCapability? = null,
    imageWidth: Int = 0,
    imageHeight: Int = 0,
): PlanContext {
    val masks = document.operations.filterIsInstance<Operation.Mask>()
    val targetRefs = masks.mapIndexed { index, mask -> mask.id to "t${index + 1}" }.toMap()
    val adjusts = document.operations.filterIsInstance<Operation.Adjust>()
    val adjustRefs = adjusts.mapIndexed { index, adjust -> adjust.id to "a${index + 1}" }.toMap()
    val order = document.operations.mapIndexed { index, op -> op.id to index + 1 }.toMap()
    val crop = document.crop()
    return PlanContext(
        adjustments = adjusts.map {
            CurrentAdjust(
                kind = it.kind,
                value = it.value,
                targetRef = it.maskId?.let(targetRefs::get),
                ref = adjustRefs.getValue(it.id),
                order = order.getValue(it.id),
            )
        },
        hasSelection = document.activeMaskId != null,
        cropped = crop != null,
        expandable = document.canOutpaint,
        targets = masks.map { mask ->
            val note = notes[mask.id]
            PlanTargetState(
                ref = targetRefs.getValue(mask.id),
                maskId = mask.id,
                active = mask.id == document.activeMaskId,
                description = note,
                source = if (note != null) TargetSource.Resolved else TargetSource.Unknown,
                order = order.getValue(mask.id),
            )
        },
        operations = document.operations.mapIndexed { index, op -> operationNote(index + 1, op, targetRefs) },
        crop = crop?.let { CropNote(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom, it.angleDeg) },
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        styles = styles,
        topaz = topaz,
    )
}

/**
 * §15: an operation the model cannot set by value, by what it is. Generated pixels and masks
 * are named, never described — the planner does not know what they show.
 */
private fun operationNote(order: Int, op: Operation, targets: Map<String, String>): OperationNote = when (op) {
    is Operation.Adjust -> OperationNote(order, "adjust ${op.kind.settingName()}", op.maskId?.let(targets::get))
    is Operation.Mask -> OperationNote(order, "selection ${targets[op.id]} (changes no pixels)")
    is Operation.CutOut -> OperationNote(order, "cut-out: everything outside deleted", targets[op.maskId])
    is Operation.GenerativeErase -> OperationNote(order, "generative erase", targets[op.maskId])
    is Operation.GenerativeFill -> OperationNote(order, "generative fill", targets[op.maskId])
    is Operation.Outpaint -> OperationNote(order, "outpaint: generated area added outside the original frame")
    is Operation.Crop -> OperationNote(order, "crop (applied last)")
    is Operation.SkinRetouch -> OperationNote(order, "skin retouch", targets[op.maskId])
    is Operation.MultiShot -> OperationNote(order, "multi-shot composite")
}

/** The name `adjust`/`adjust_color_range` use on the wire: one lowercase word or "band channel". */
private fun AdjustKind.settingName(): String =
    hsl?.let { "${it.band.name.lowercase()} ${it.channel.name.lowercase()}" } ?: name.lowercase()

/**
 * specs/style_match.md §6, vibe_edit.md §15: each catalogued look as the values it writes at
 * intensity 100 — the same converted `params` the 스타일 sheet and `PlanRunner` scale. An id
 * `StyleId` does not mirror is left out rather than guessed.
 */
fun styleEffectsOf(presets: List<StylePreset>): List<StyleEffect> = presets.mapNotNull { preset ->
    StyleId.entries.firstOrNull { it.id == preset.id }
        ?.let { StyleEffect(it, preset.params.atIntensity(FULL_INTENSITY)) }
}

/**
 * §16: what the Topaz sheet would be given. The size is the stored source's — already the working
 * size — times the crop's share; an outpainted canvas has no size known without rendering, and
 * says so rather than guessing. The 화질 개선 sheet checks the real render again.
 */
fun topazCapabilityOf(configured: Boolean, document: EditDocument): TopazCapability {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(document.source.path, bounds)
    val known = bounds.outWidth > 0 && bounds.outHeight > 0 && document.outpaint() == null
    val rect = document.crop()?.rect
    return TopazCapability(
        configured = configured,
        inputWidth = if (known) (bounds.outWidth * (rect?.width() ?: 1f)).roundToInt() else null,
        inputHeight = if (known) (bounds.outHeight * (rect?.height() ?: 1f)).roundToInt() else null,
        hasAlpha = document.hasAlpha,
    )
}

private const val FULL_INTENSITY = 100

/**
 * What a proposal was asked from. Two asks with equal snapshots would send the same request: the
 * same document, words, mode, settings and session notes.
 */
private data class PlanSnapshot(
    /** The document the frame was rendered from; its `id` is the project. */
    val document: EditDocument,
    val text: String,
    val mode: PlanMode,
    /** Bumped by every Gemini or Topaz settings change. */
    val settings: Int,
    val notes: Map<String, String>,
)

/** specs/vibe_edit.md §14. The asking half of [DirectState]. */
data class ProposalState(
    val phase: ProposalPhase = ProposalPhase.Idle,
    val mode: PlanMode = PlanMode.Direct,
    /** The answered proposal under review, whatever its outcome. Never persisted. */
    val proposal: PlanProposal? = null,
    val frameReady: Boolean = false,
    /** §19: the snapshot [proposal] was asked with — a choice of items is checked against it. */
    val context: PlanContext? = null,
)

/**
 * specs/vibe_edit.md §14, §15. Owns one planning request at a time, for 보정 추천 (inline) and a
 * sent 지시 (the overlay) alike. A request is pinned to a [PlanSnapshot] — the document the frame
 * was rendered from, the text verbatim, the mode, the Gemini and Topaz settings generation and the
 * session's confirmed selections — and a sequence number every invalidation bumps, so an answer
 * for anything that has since changed is dropped even when the provider ignored its cancellation.
 * The context sent is built from the same snapshot, once, and kept with the answer.
 *
 * It never edits and never plans on its own: only [ask] sends, and only with a frame of the
 * document on screen. A 지시 sent before that frame exists waits for it.
 */
// Each parameter is one thing a request depends on or reports to; §15 added the snapshot's two.
@Suppress("LongParameterList")
class DirectProposals internal constructor(
    private val provider: EditPlanProvider,
    private val runner: PlanRunner,
    private val scope: CoroutineScope,
    settingsChanges: Flow<Any>,
    /** §15, §19: the session's found selections, mask id → phrase; part of every snapshot. */
    private val notes: () -> Map<String, String>,
    /** §15: the full context for a document, its frame and the notes — styles and Topaz included. */
    private val contextOf: suspend (EditDocument, Bitmap, Map<String, String>) -> PlanContext,
    /** Anything that drops the proposal drops what was built from it (§18's candidate). */
    private val onInvalidated: () -> Unit,
    private val onFailure: (AppError, PlanMode) -> Unit,
) {

    private val _state = MutableStateFlow(ProposalState())
    val state: StateFlow<ProposalState> = _state.asStateFlow()

    private var document: EditDocument? = null
    private var frame: Bitmap? = null
    private var waiting: Pair<String, PlanMode>? = null
    private var asked: PlanSnapshot? = null
    private var cached: Triple<PlanSnapshot, PlanProposal, PlanContext>? = null
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
            // §18: a run builds a candidate and commits nothing, so any change to the document
            // — undo, redo, another project — is one the proposal was not asked about.
            invalidate()
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
                _state.value = _state.value.copy(
                    phase = ProposalPhase.Loading,
                    mode = mode,
                    proposal = null,
                    context = null,
                )
            }
            return
        }
        val hit = cached?.takeIf { !force && it.first == snapshotOf(text, mode, document) }
        if (hit != null) {
            stop()
            asked = hit.first
            show(hit.second, hit.first, hit.third)
        } else {
            send(text, mode, document, preview)
        }
    }

    private fun send(text: String, mode: PlanMode, document: EditDocument, preview: Bitmap) {
        stop()
        val snapshot = snapshotOf(text, mode, document)
        val mine = seq
        asked = snapshot
        _state.value = _state.value.copy(phase = ProposalPhase.Loading, mode = mode, proposal = null, context = null)
        job = scope.launch {
            val context = contextOf(document, preview, snapshot.notes)
            if (mine != seq) return@launch
            val result = provider.plan(preview, PlanRequest(text, mode, context))
            if (mine != seq) return@launch
            when (result) {
                is Result.Success -> {
                    cached = Triple(snapshot, result.value, context)
                    show(result.value, snapshot, context)
                }
                is Result.Failure -> fail(result.error, mode)
            }
        }
    }

    /** §9.1 still applies on arrival: a ready plan the runner would refuse is not shown as ready. */
    private fun show(proposal: PlanProposal, snapshot: PlanSnapshot, context: PlanContext) {
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
            context = context.takeUnless { phase == ProposalPhase.NotUnderstood },
        )
    }

    /** §14: a refused envelope is the hint; anything else is a failure the user can retry. */
    private fun fail(error: AppError, mode: PlanMode) {
        val refused = error is AppError.Invalid && error.detail.startsWith(UNPLANNABLE_PREFIX)
        _state.value = _state.value.copy(
            phase = if (refused) ProposalPhase.NotUnderstood else ProposalPhase.Failed,
            proposal = null,
            context = null,
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
        val same = snapshot.document == document && snapshot.text == text && snapshot.settings == settings &&
            snapshot.notes == notes()
        return current.proposal.takeIf { current.phase == ProposalPhase.Ready && same }
    }


    /** 취소, inline or on the overlay: the request is dropped and the bar keeps its text. */
    fun cancel() {
        if (_state.value.phase != ProposalPhase.Loading) return
        stop()
        cached = null
        asked = null
        _state.value = _state.value.copy(phase = ProposalPhase.Cancelled, proposal = null, context = null)
    }

    /** Any change to what a proposal was asked from: the answer, the cache and the request go. */
    fun invalidate() {
        stop()
        cached = null
        asked = null
        _state.value = _state.value.copy(phase = ProposalPhase.Idle, proposal = null, context = null)
        onInvalidated()
    }

    private fun stop() {
        seq++
        waiting = null
        job?.cancel()
        job = null
    }

    private fun snapshotOf(text: String, mode: PlanMode, document: EditDocument) =
        PlanSnapshot(document, text, mode, settings, notes())

    private companion object {
        /** Duplicated from `core:ai` for the reason `isSafetyBlock`'s prefix is: it is internal there. */
        const val UNPLANNABLE_PREFIX = "unplannable:"
    }
}
