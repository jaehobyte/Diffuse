package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.ConstraintKind
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanItem
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.TopazBlock
import com.diffuse.core.ai.items
import com.diffuse.core.ai.selected
import com.diffuse.core.ai.selectionProblem
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazOutcome
import com.diffuse.core.ai.topazBlock
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.TopazSessionFiles
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import com.diffuse.feature.editor.tools.enhance.TopazProcessor
import com.diffuse.feature.editor.tools.enhance.TopazResult
import com.diffuse.feature.editor.tools.enhance.blockerFor
import com.diffuse.feature.editor.tools.enhance.messageFor
import com.diffuse.feature.editor.tools.enhance.phase
import com.diffuse.feature.editor.tools.isSafetyBlock
import com.diffuse.feature.editor.tools.select.MaskOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

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
 * What the tool cannot know for itself: what the canvas is showing, where history lives, that a
 * run invalidates the 선택 tool's session (§9.4), that the sheet closes when a candidate is
 * applied (§3), and how to render and where files live (§18). The Topaz step runs through the
 * shared `TopazProcessor` instead (§19). `EditorViewModel` is the one object that knows all of it.
 */
interface DirectHost {

    fun canvas(): DirectCanvas?

    /** §18: the reviewed candidate, as **one** history entry, no coalesce key. */
    fun commit(document: EditDocument)

    suspend fun releaseSession()

    /**
     * §3: the sheet closes once a local candidate is applied. §19: nothing opens after it — a
     * plan's crop is already in the candidate the user reviewed, and its Topaz step already ran.
     */
    fun onFinished()

    /** §19: why [step] cannot run on [document] — its real size, alpha and the key. */
    suspend fun topazBlock(step: PlanStep.Topaz, document: EditDocument): TopazBlock?

    /** §18: [document] at preview size, off the main thread; null when the render failed. */
    suspend fun render(document: EditDocument): Bitmap?

    /** §18: one of [document]'s masks at preview size, for the overlay and the outside check. */
    suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap?

    /** §18: deletes the files a discarded candidate wrote; nothing else references them. */
    suspend fun discardFiles(paths: Set<String>)
}

/** specs/vibe_edit.md §3, §14, §18. Sheet state; nothing here reaches the document until 적용. */
data class DirectState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val request: String = "",
    /** §14: an unedited example may be swapped for another; a typed sentence is the user's. */
    val requestSource: RequestSource = RequestSource.Typed,
    /** §14: the request in flight or answered — the proposal, its outcome and its mode. */
    val proposals: ProposalState = ProposalState(),
    /** §18: the plan's local result, built off the document, until it is applied or dropped. */
    val candidate: CandidateState? = null,
    val message: DirectMessage? = null,
    val suggestions: SuggestionState = SuggestionState(),
    /**
     * §19: the ids of the items the user has on, for the proposal under review. Every runnable
     * item starts on; the proposal itself is never changed by a toggle.
     */
    val chosen: Set<String> = emptySet(),
    /** §19: why an item cannot run now, by item id — shown on its row; on, it blocks the primary. */
    val blocks: Map<String, TopazBlock> = emptyMap(),
    /** One-shot: the new edit copy a Topaz result was opened as (D098). Consumed by the route. */
    val openedProjectId: String? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready

    val phase: ProposalPhase get() = proposals.phase

    val proposal: PlanProposal? get() = proposals.proposal

    /** §18, §19: the local steps, their checks or the Topaz step are running. */
    val running: Boolean
        get() = candidate?.phase == CandidatePhase.Running || candidate?.phase == CandidatePhase.Checking

    /** §19: 고해상도 저장 or 새 편집본으로 열기 is reading the Topaz result. */
    val keeping: Boolean
        get() = candidate?.topazPhase == TopazPhase.Saving || candidate?.topazPhase == TopazPhase.Opening

    /** DESIGN.md §7: a sent 지시 plans behind the overlay. */
    val planning: Boolean get() = phase == ProposalPhase.Loading && proposals.mode == PlanMode.Direct

    /** §14: 보정 추천 runs inline, with its own 취소, and keeps the bar usable. */
    val recommending: Boolean get() = phase == ProposalPhase.Loading && proposals.mode == PlanMode.Recommend

    /** DESIGN.md §7: the planning call, the run and a save or copy show progress and a way out. */
    val working: Boolean get() = planning || running || keeping

    /** §19: the ready proposal's toggleable items, in workflow order. */
    val items: List<PlanItem> get() = proposal?.takeIf { phase == ProposalPhase.Ready }?.items.orEmpty()

    /** §19: the reviewed proposal with only the chosen items (and the selections they need). */
    val selection: PlanProposal? get() = proposal?.takeIf { phase == ProposalPhase.Ready }?.selected(chosen)

    /**
     * The plan waiting for 선택한 보정 실행 — what the user chose of a ready proposal, Topaz
     * included. It is never persisted (§13).
     */
    val plan: EditPlan? get() = selection?.plan

    /** §19: what a run builds locally — the chosen steps without the Topaz step. */
    val localPlan: EditPlan? get() = plan?.let { EditPlan(it.steps.filterNot { step -> step is PlanStep.Topaz }) }

    /** §16, §19: the chosen Topaz step, run last on the local candidate's full render. */
    val topazStep: PlanStep.Topaz? get() = plan?.steps?.firstNotNullOfOrNull { it as? PlanStep.Topaz }

    /** §19: the chosen local items — what a candidate is built for. */
    val localChosen: Set<String>
        get() = items.filter { it.id in chosen && it.step !is PlanStep.Topaz }.map { it.id }.toSet()

    /** §19: a ready proposal with every item off: "적용할 보정을 선택하세요". */
    val nothingChosen: Boolean get() = phase == ProposalPhase.Ready && items.none { it.id in chosen }

    /** §19: an item that is on cannot run now; turning it off is the way on. */
    val chosenBlocked: Boolean get() = chosen.any { it in blocks }

    /**
     * §19: the whole chosen result exists — the local candidate for the current choice and, when
     * the Topaz item is on, its result for exactly that candidate. Only this is shown as 후.
     */
    val complete: Boolean
        get() = candidate?.phase == CandidatePhase.Review && candidate.chosen == localChosen &&
            (topazStep == null || candidate.topaz != null)

    /** §18, §19: the local candidate the canvas draws — only a complete result's, else the input. */
    val shownDocument: EditDocument? get() = candidate?.document?.takeIf { complete }

    /** §19: the Topaz item is on and has no result for this candidate — it must run (again). */
    val needsTopaz: Boolean
        get() = topazStep != null && candidate?.phase == CandidatePhase.Review && candidate.topaz == null

    /** §19: toggles move while nothing runs, no selection waits and nothing reads the result. */
    val canToggle: Boolean
        get() = items.isNotEmpty() && !planning && when (candidate?.phase) {
            null, CandidatePhase.Stale -> true
            CandidatePhase.Review -> candidate.topazPhase == null
            CandidatePhase.Running, CandidatePhase.Confirming, CandidatePhase.Checking -> false
        }

    /**
     * The sheet's one primary: 선택한 보정 실행 for a ready plan, 선택 확인 while an existing
     * selection is on the overlay, 선택한 보정 다시 실행 for a stale candidate or one whose Topaz step
     * must run again, and — for a candidate the local checks passed that matches the current choice —
     * 적용 without Topaz or 새 편집본으로 열기 with its result.
     */
    val canApply: Boolean
        get() = !chosenBlocked && when (candidate?.phase) {
            null -> plan != null && !working && !nothingChosen
            CandidatePhase.Confirming -> true
            CandidatePhase.Review ->
                candidate.canAdopt && candidate.chosen == localChosen && candidate.topazPhase == null
            CandidatePhase.Stale -> !nothingChosen
            CandidatePhase.Running, CandidatePhase.Checking -> false
        }

    /** §10: a refused answer is a hint under the bar, never a snackbar and never a dialog. */
    val notUnderstood: Boolean get() = phase == ProposalPhase.NotUnderstood

    /** §14: the 보정 추천 entry needs a frame of the document on screen and nothing else running. */
    val canRecommend: Boolean
        get() = enabled && proposals.frameReady && !working && !recommending && candidate == null

    /**
     * §14: the example sentences, only over an empty bar or an unedited example, so they can
     * never overwrite what the user wrote, and never while a run is changing the photo. A
     * proposal under review takes their place, so its sections stay inside the 45% sheet.
     */
    val showExamples: Boolean
        get() = !suggestions.hidden && candidate == null && proposal == null &&
            (request.isEmpty() || requestSource == RequestSource.Suggestion)
}

/**
 * specs/vibe_edit.md §3, §10, §14, §18, §19. Owns the proposal between the response and 적용, the
 * user's choice of its items, and the workflow run from that choice. A ready plan is not a good
 * result: one 선택한 보정 실행 runs every chosen step to the end — the local steps into a candidate
 * that is never in the document, its history or its autosave (selections the plan's phrases find
 * are used without asking; only an existing selection of unknown meaning waits on the overlay),
 * then the chosen Topaz step on that candidate's full render. The user compares the whole result
 * 전/후 on this screen, turns items off or on again (rebuilt from the same input), and keeps it:
 * without Topaz 적용 — one history entry, one undo — and with it D098's 고해상도 저장 or
 * 새 편집본으로 열기, which leave this document alone. Only `EditorViewModel` holds the
 * `HistoryStack`, so committing is the host's.
 *
 * §19: a **proposal session** runs from the first run to the proposal's end (applied, invalidated
 * or closed). The selections, generated patches and Topaz results made in it are kept for reuse
 * ([PlanCache], [topazResults]); every file they wrote is deleted when the session ends — after a
 * save or copy reading one is done — except the ones the applied document references.
 */
// One public function per thing the sheet can ask for; §18 added the candidate's five, §19 toggle.
@Suppress("TooManyFunctions", "LongParameterList")
class DirectController(
    provider: EditPlanProvider,
    private val runner: PlanRunner,
    private val scope: CoroutineScope,
    private val host: DirectHost,
    /** §14: the Gemini and Topaz settings; any change drops the proposal asked under the old ones. */
    settingsChanges: Flow<Any>,
    /** §15: the snapshot's context — styles and Topaz capability are the ViewModel's to read. */
    contextOf: suspend (EditDocument, Bitmap, Map<String, String>) -> PlanContext,
    private val dispatchers: DispatcherProvider,
    /** §19: the 화질 개선 sheet's own processing — render, submit once, decode — and its host. */
    private val topaz: TopazProcessor,
) {

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    private var run: Job? = null

    /**
     * §19: the run's identity — bumped by every run, rebuild, drop and invalidation, so a late step,
     * check, Topaz answer, save or copy never lands on a newer choice or a closed sheet.
     */
    private var runSeq = 0

    /**
     * §18: the canvas the session's candidates are built from — its document must still be on
     * screen at 적용, and its frame is what every rebuild segments and is compared with.
     */
    private var base: DirectCanvas? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    /** §19: this session's confirmed selections and generated patches. */
    private val cache = PlanCache()

    /** §19: every file a run of this session wrote; deleted when the session ends. */
    private val sessionFiles = mutableSetOf<String>()

    /**
     * §15, §19: this editor session's applied plan selections, mask id → the phrase they were found
     * for — described to the planner as found, never as confirmed. Never persisted.
     */
    private val notes = mutableMapOf<String, String>()

    /** §15: existing selections the user confirmed the meaning of in this session. */
    private val confirmedIds = mutableSetOf<String>()

    /**
     * What this proposal session's runs established: selections found for a phrase and existing ones
     * confirmed on the overlay, until it is applied or ends.
     */
    private val runChecks = mutableListOf<TargetCheck>()

    /**
     * §19: this session's Topaz results by exactly what they were made from — the full candidate the
     * upload was rendered from, the item, and the key's generation. A local `Adjust` changes that
     * input, so `PlanCache`'s generated-patch key is not reused for it.
     */
    private val topazResults = mutableMapOf<TopazKey, TopazResult>()

    /** §19: the session's Topaz folder (upload and results); discarded when the session ends. */
    private var topazFiles: TopazSessionFiles? = null
    private var topazRun = 0L

    /** Every save or new copy still reading a Topaz result; the session's end waits for them all. */
    private val readers = mutableListOf<Job>()

    /** Which 새 편집본으로 열기 is the one waited for: bumped by each tap and by its cancel. */
    private var copySeq = 0

    /** specs/vibe_edit.md §14: the one planning request, for 보정 추천 and a sent 지시 alike. */
    val proposals = DirectProposals(
        provider = provider,
        runner = runner,
        scope = scope,
        settingsChanges = settingsChanges,
        notes = { notes.toMap() },
        contextOf = contextOf,
        onInvalidated = {
            dropCandidate()
            endSession()
        },
    ) { error, mode ->
        showMessage(
            when {
                error is AppError.Unauthorized -> R.string.direct_needs_key
                error.isSafetyBlock() -> R.string.direct_blocked
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
            proposals.state.collect { onProposals(it) }
        }
    }

    /**
     * §19: a new proposal — or none — starts a new choice: every item on unless it cannot run
     * (a Topaz item without a key, or one the snapshot's input cannot take), which starts off and
     * says why. The same proposal keeps the user's choice.
     */
    private fun onProposals(proposals: ProposalState) {
        val state = _state.value
        if (proposals.proposal == state.proposals.proposal) {
            _state.value = state.copy(proposals = proposals)
            return
        }
        val items = proposals.proposal?.takeIf { proposals.phase == ProposalPhase.Ready }?.items.orEmpty()
        val blocks = items.mapNotNull { item ->
            (item.step as? PlanStep.Topaz)?.let { topazBlock(it, proposals.context?.topaz) }?.let { item.id to it }
        }.toMap()
        _state.value = state.copy(
            proposals = proposals,
            chosen = items.map { it.id }.filterNot { it in blocks }.toSet(),
            blocks = blocks,
        )
    }

    /** §10: a blank key greys the tool and opens the 서버 설정 sheet rather than a snackbar. */
    fun onToolTapped(): DirectTap =
        if (_state.value.enabled) {
            DirectTap.Open
        } else {
            showMessage(R.string.direct_needs_key)
            DirectTap.OpenSettings
        }

    /**
     * §14, §18: what the canvas is showing, with a frame only when it was rendered from it. A
     * different document — undo, redo, another project — drops the proposal and any candidate.
     */
    fun onCanvas(document: EditDocument?, preview: Bitmap?) {
        val hadCandidate = _state.value.candidate != null
        proposals.onCanvas(document, preview)
        if (hadCandidate && _state.value.candidate == null) showMessage(R.string.direct_stale)
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
        if (state.candidate != null || (request == state.request && source == state.requestSource)) return
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
        if (request.isBlank() || state.candidate != null || !state.enabled) return
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

    /** The sheet's primary, whatever it says right now (see [DirectState.canApply]). */
    fun apply() {
        val state = _state.value
        when (state.candidate?.phase) {
            null, CandidatePhase.Stale -> preview()
            CandidatePhase.Confirming -> answerSelection(true)
            CandidatePhase.Review -> when {
                state.topazStep == null -> adopt()
                state.candidate.topaz != null -> openCopy()
                else -> rerunTopaz()
            }
            CandidatePhase.Running, CandidatePhase.Checking -> Unit
        }
    }

    /**
     * §19: one item on or off. Nothing is called and nothing is edited for it: before a run only
     * the choice changes. With a candidate, a different local choice rebuilds it from the same
     * input with only the chosen steps — locally, from what this session already made; a step that
     * would need a remote call leaves the candidate [CandidatePhase.Stale] until 다시 실행. The
     * Topaz item alone moving shows this session's result for the same candidate, or none: it is
     * never run by a toggle. Turning every item off drops the candidate.
     */
    fun toggle(id: String) {
        val state = _state.value
        if (!state.canToggle || state.items.none { it.id == id }) return
        val chosen = if (id in state.chosen) state.chosen - id else state.chosen + id
        _state.value = state.copy(chosen = chosen, message = null)
        val candidate = state.candidate ?: return
        val now = _state.value
        when {
            now.nothingChosen -> dropCandidate()
            now.localChosen != candidate.chosen -> rebuild(candidate.reduced)
            else -> refreshTopaz()
        }
    }

    /**
     * §18, §19 선택한 보정 실행: runs the reviewed plan — never a new one — as the user chose it, to
     * the end: the local steps into a candidate, then the chosen Topaz step on it, while the
     * document, the text, the settings and the session notes it was asked from still hold. Nothing
     * is committed; a failure, a "no" on the overlay or a cancel leaves the document untouched.
     */
    // Stale and an invalid choice are two ways out before the run.
    @Suppress("ReturnCount")
    fun preview() {
        val state = _state.value
        val canvas = host.canvas()
        val idle = state.candidate?.phase.let { it == null || it == CandidatePhase.Stale }
        if (!state.canApply || !idle || canvas == null) return
        val proposal = proposals.readyFor(canvas.document, state.request)
        if (proposal == null) {
            proposals.invalidate()
            showMessage(R.string.direct_stale)
            return
        }
        if (!runnable(proposal.selected(state.chosen), state, canvas.document)) {
            showMessage(R.string.direct_choice_invalid)
            return
        }
        val local = state.localPlan ?: return
        val session = base?.takeIf { it.document == canvas.document } ?: canvas.also {
            endSession()
            base = it
        }
        _state.value = _state.value.copy(message = null)
        execute(local, session, offline = false)
    }

    /** §19: the choice passes the proposal's rules again, and the runner's on [document]. */
    private fun runnable(choice: PlanProposal, state: DirectState, document: EditDocument): Boolean =
        choice.selectionProblem(state.proposals.context ?: PlanContext()) == null &&
            runner.validate(choice.plan, document) == null

    /** §19: the new choice from the same input, without a remote call. */
    private fun rebuild(wasReduced: Boolean) {
        val canvas = base ?: return
        val plan = _state.value.localPlan ?: return
        // §19: 강도 줄이기 belongs to the candidate it halved; the new one is at full strength.
        if (wasReduced) showMessage(R.string.direct_choice_restrength)
        execute(plan, canvas, offline = true)
    }

    /**
     * §19: [plan]'s local steps, then — unless [offline] — the chosen Topaz step on their result.
     * [offline] is a rebuild after a toggle: it calls nothing remote, Topaz included.
     */
    private fun execute(plan: EditPlan, canvas: DirectCanvas, offline: Boolean) {
        run?.cancel()
        val mine = ++runSeq
        val chosen = _state.value.localChosen
        val steps = plan.steps.size + if (_state.value.topazStep != null) 1 else 0
        setCandidate(CandidateState(CandidatePhase.Running, steps = steps, chosen = chosen))
        run = scope.launch {
            // The runner works on IO; the overlay's question touches this controller's state, so it
            // is asked back on the dispatcher this run was launched on.
            val ui = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
            if (!offline && plan.steps.any { it is PlanStep.Select }) host.releaseSession()
            var built = canvas.document
            var error: AppError? = null
            // Reported from the runner's thread; read here, on the main one, once the run ends.
            val found = CopyOnWriteArrayList<TargetCheck>()
            runner.run(
                plan = plan,
                document = canvas.document,
                preview = canvas.preview,
                activeMask = canvas.activeMask,
                sourceAspect = canvas.sourceAspect,
                confirm = { check -> withContext(ui) { confirm(check) } },
                confirmed = confirmedIds + notes.keys + runChecks.map { it.maskId },
                cache = cache,
                offline = offline,
                onResolved = found::add,
            ).collect { event ->
                when (event) {
                    is RunEvent.Started ->
                        if (mine == runSeq) setCandidate(_state.value.candidate?.copy(step = event.index))
                    is RunEvent.Committed -> {
                        built = event.document
                        // §19: the session owns every file, kept for reuse until it ends.
                        sessionFiles += createdFiles(canvas.document, built)
                    }
                    is RunEvent.Stopped -> error = event.error
                    RunEvent.Completed -> Unit
                }
            }
            if (mine != runSeq) return@launch
            runChecks += found
            val failed = error
            when {
                failed == null -> review(mine, canvas.document, built, chosen, online = !offline)
                (failed as? AppError.Invalid)?.detail == PlanRunner.NEEDS_PREVIEW ->
                    setCandidate(CandidateState(CandidatePhase.Stale, chosen = chosen))
                else -> endCandidate(messageOf(failed))
            }
        }
    }

    /** §18: the overlay's question, asked from the runner; answered by [answerSelection]. */
    private suspend fun confirm(check: TargetCheck): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingConfirm = answer
        setCandidate(_state.value.candidate?.copy(phase = CandidatePhase.Confirming, check = check))
        return try {
            answer.await().also { yes -> if (yes) runChecks += check }
        } finally {
            pendingConfirm = null
            setCandidate(_state.value.candidate?.copy(phase = CandidatePhase.Running, check = null))
        }
    }

    /** §18: 선택 확인 (yes) or 중단 (no). A no stops the run before anything uses the selection. */
    fun answerSelection(yes: Boolean) {
        pendingConfirm?.complete(yes)
    }

    /**
     * §18: the local candidate is built; the local checks run before it can be kept. §19: the Topaz
     * item is checked again against the candidate — its real size once cropped, its alpha — and,
     * on a run ([online]), runs on it straight away unless that check or a protected change stops
     * it. A rebuild shows this session's result for the same candidate, or none.
     */
    private suspend fun review(
        mine: Int,
        input: EditDocument,
        candidate: EditDocument,
        chosen: Set<String>,
        online: Boolean,
    ) {
        setCandidate(_state.value.candidate?.copy(phase = CandidatePhase.Checking))
        val before = host.render(input)
        val findings = findings(input, candidate)
        val topazBlocks = topazBlocks(candidate)
        if (mine != runSeq) return
        val step = _state.value.topazStep
        val reviewed = CandidateState(
            phase = CandidatePhase.Review,
            steps = _state.value.candidate?.steps ?: 0,
            document = candidate,
            before = before,
            reducible = hasNumericChange(input, candidate),
            findings = findings,
            chosen = chosen,
            topaz = step?.let { cachedTopaz(candidate, it) },
            resolved = runChecks.filter { candidate.mask(it.maskId) != null && input.mask(it.maskId) == null }
                .mapNotNull { it.phrase },
        )
        _state.value = _state.value.copy(candidate = reviewed, blocks = topazBlocks)
        if (!online || step == null || reviewed.topaz != null) return
        when {
            _state.value.chosenBlocked -> showMessage(R.string.direct_topaz_blocked)
            reviewed.canAdopt -> runTopaz(mine, reviewed, step)
        }
    }

    /** §19: the Topaz items' blocks for the document they would be handed. */
    private suspend fun topazBlocks(document: EditDocument): Map<String, TopazBlock> =
        _state.value.items.mapNotNull { item ->
            (item.step as? PlanStep.Topaz)?.let { host.topazBlock(it, document) }?.let { item.id to it }
        }.toMap()

    /**
     * §19: the Topaz step on [reviewed]'s candidate — rendered in full (crop and every local edit
     * included), checked for its real size, alpha and options, then submitted exactly once through
     * the 화질 개선 sheet's own processing. Only the run [mine] may land: a cancel, a new choice or
     * any invalidation makes a late stage, result or failure touch nothing, and a late result's file
     * is deleted. A failure keeps the local candidate for review with the Topaz item off.
     */
    // Each early return is a different way the step does not reach the provider or does not land.
    @Suppress("ReturnCount")
    private suspend fun runTopaz(mine: Int, reviewed: CandidateState, step: PlanStep.Topaz) {
        val document = reviewed.document ?: return
        val stage = reviewed.copy(
            phase = CandidatePhase.Running,
            step = (reviewed.steps - 1).coerceAtLeast(0),
            topazPhase = TopazPhase.Preparing,
            topaz = null,
            topazFailure = null,
            comparing = false,
        )
        setCandidate(stage)
        val files = topazFilesFor(mine) ?: return topazStopped(mine, reviewed, null, R.string.topaz_prepare_failed)
        val input = topaz.prepare(document, files)
        if (mine != runSeq) return
        if (input == null) return topazStopped(mine, reviewed, null, R.string.topaz_prepare_failed)
        val options = optionsFor(step)
        val job = TopazJob(step.purpose, options, input.width, input.height, input.hasAlpha)
        job.problem()?.let { return topazStopped(mine, reviewed, it, blockerFor(it)) }
        val outcome = topaz.process(job, files, ++topazRun, input, options) { reported ->
            val (phase, progress) = reported.phase()
            if (mine == runSeq) setCandidate(_state.value.candidate?.copy(topazPhase = phase, topazProgress = progress))
        }
        // A provider that ignored its cancellation still lands nothing, and its file goes.
        if (mine != runSeq || !currentCoroutineContext().isActive) {
            (outcome as? TopazOutcome.Success)?.let { topaz.delete(it.value.file) }
            return
        }
        when (outcome) {
            is TopazOutcome.Success -> {
                topazResults[topazKey(document, step)] = outcome.value
                setCandidate(reviewed.copy(topaz = outcome.value))
            }
            is TopazOutcome.Failure -> topazStopped(mine, reviewed, outcome.failure, messageFor(outcome.failure))
        }
    }

    /** §19: the Topaz step stopped before a result; the local candidate stays under review. */
    private fun topazStopped(mine: Int, reviewed: CandidateState, failure: TopazFailure?, @StringRes message: Int) {
        if (mine != runSeq) return
        _state.value = _state.value.copy(
            candidate = reviewed.copy(topaz = null, topazFailure = failure),
            message = DirectMessage(message),
        )
    }

    /** The session's Topaz folder, opened once; one opened for a run that is no longer wanted goes. */
    private suspend fun topazFilesFor(mine: Int): TopazSessionFiles? {
        val files = topazFiles ?: (topaz.open() as? Result.Success)?.value
        when {
            files == null || files === topazFiles -> Unit
            mine == runSeq -> topazFiles = files
            else -> withContext(NonCancellable) { topaz.discard(files) }
        }
        return files?.takeIf { it === topazFiles }
    }

    /** §19: this session's result for exactly [document], [step] and the current key, if any. */
    private fun cachedTopaz(document: EditDocument, step: PlanStep.Topaz): TopazResult? =
        topazResults[topazKey(document, step)]

    private fun topazKey(document: EditDocument, step: PlanStep.Topaz) =
        TopazKey(document.source, document.renderedOperations(), step, topaz.config.value.generation)

    /** §16: the plan's options when its purpose offers them, else the purpose's defaults — the sheet's rule. */
    private fun optionsFor(step: PlanStep.Topaz): TopazOptions =
        step.options.takeIf { TopazJob(step.purpose, it, 1, 1).problem() == null }
            ?: TopazOptions.defaultsFor(step.purpose)

    /** §19: only the Topaz item moved — the same candidate, with or without its kept result. */
    private fun refreshTopaz() {
        val candidate = _state.value.candidate?.takeIf { it.phase == CandidatePhase.Review } ?: return
        val document = candidate.document ?: return
        val result = _state.value.topazStep?.let { cachedTopaz(document, it) }
        setCandidate(candidate.copy(topaz = result, topazFailure = null, saved = false, comparing = false))
    }

    /**
     * §19 선택한 보정 다시 실행 for the Topaz step alone: the candidate under review is still the
     * current choice, but has no result — it never ran for this input, it failed, or 강도 줄이기
     * changed it. One tap, one submit; never automatic.
     */
    // Not runnable, no item, stale: three ways the tap is not a run.
    @Suppress("ReturnCount")
    private fun rerunTopaz() {
        val state = _state.value
        val candidate = state.candidate?.takeIf { state.canApply && state.needsTopaz } ?: return
        val step = state.topazStep ?: return
        val input = base?.document
        if (input == null || !stillHolds(input, state.request)) return stale()
        run?.cancel()
        val mine = ++runSeq
        run = scope.launch { runTopaz(mine, candidate, step) }
    }

    /**
     * §18's code half of the comparison: what the chosen plan's shape says, and — under an
     * explicit 대상만 / 배경 유지 — whether pixels outside the selections it used changed. The
     * frames are compared without the crop, so a reframing is told apart from a colour change.
     * Whether the selection is the right thing is the overlay's question, not this one's.
     */
    private suspend fun findings(input: EditDocument, candidate: EditDocument): Set<CandidateFinding> {
        val steps = _state.value.localPlan?.steps.orEmpty()
        // §19: only what the user said is a rule; the model's guesses do not block 적용.
        val kinds = _state.value.proposal?.intent?.constraints.orEmpty().filter { it.explicit }.map { it.kind }.toSet()
        return buildSet {
            if (steps.any { it is PlanStep.Crop }) add(CandidateFinding.FrameCropped)
            if (steps.any { it is PlanStep.Erase || it is PlanStep.Fill }) add(CandidateFinding.Generated)
            val colourRule = ConstraintKind.KeepColors in kinds || ConstraintKind.KeepHue in kinds
            if (colourRule && steps.any { it is PlanStep.Adjust && it.kind in TONAL_KINDS }) {
                add(CandidateFinding.TonalUnderColorRule)
            }
            val outsideKept = ConstraintKind.SubjectOnly in kinds || ConstraintKind.KeepBackground in kinds
            if (outsideKept && outsideChanged(steps, input, candidate)) add(CandidateFinding.ProtectedChanged)
        }
    }

    // A frame that did not render is a change nobody can rule out: two early answers.
    @Suppress("ReturnCount")
    private suspend fun outsideChanged(
        steps: List<PlanStep>,
        input: EditDocument,
        candidate: EditDocument,
    ): Boolean {
        val before = host.render(input.withoutCrop()) ?: return true
        val after = host.render(candidate.withoutCrop()) ?: return true
        val existing = steps.mapNotNull { ((it as? PlanStep.Adjust)?.target as? PlanTarget.Existing)?.maskId }
        val created = candidate.operations.filterIsInstance<Operation.Mask>().map { it.id }
            .filter { id -> input.mask(id) == null }
        val masks = (existing + created).distinct().mapNotNull { host.resolveMask(candidate, it) }
        return withContext(dispatchers.default) {
            val allowed = masks.map { MaskOps.dilated(it, maxOf(1, it.width / EDGE_MARGIN_DIVISOR)) }
                .let(MaskOps::union)
            outsideChange(before, after, allowed) > OUTSIDE_CHANGE_LIMIT
        }
    }

    /**
     * §18 적용: the reviewed local candidate, once, while the document it was built from is still
     * the one on screen and the proposal still holds, and only when it is the current choice. One
     * history entry, so one undo returns to the input; nothing is re-planned or re-rendered by a
     * server. §19: the session's other files go, and nothing opens after it.
     */
    // Not reviewable, no document, stale: three ways the tap is not an 적용.
    @Suppress("ReturnCount")
    fun adopt() {
        val state = _state.value
        val candidate = state.candidate
            ?.takeIf { state.canApply && it.phase == CandidatePhase.Review && state.topazStep == null } ?: return
        val document = candidate.document ?: return
        val input = base?.document
        if (input == null || !stillHolds(input, state.request)) return stale()
        // Cleared first: the invalidation below must not delete the files it now references.
        _state.value = _state.value.copy(candidate = null)
        runChecks.filter { document.mask(it.maskId) != null }.forEach { check ->
            check.phrase?.let { notes[check.maskId] = it } ?: confirmedIds.add(check.maskId)
        }
        endSession(keep = document.paths())
        host.commit(document)
        _state.value = _state.value.copy(request = "", requestSource = RequestSource.Typed)
        proposals.invalidate()
        host.onFinished()
    }

    /**
     * §19, D098 새 편집본으로 열기: the reviewed Topaz result becomes a new project, created once; this
     * document and its history are untouched, and nothing is re-applied to the copy — the local
     * edits are already in its pixels. A create nobody waits for any more (cancel, close, a new
     * choice) is deleted rather than left in Browse; a failed one keeps the result for a retry that
     * calls Topaz again never.
     */
    // Not keepable, stale: two ways the tap is not a copy.
    @Suppress("ReturnCount")
    fun openCopy() {
        val state = _state.value
        val candidate = state.candidate?.takeIf { state.canApply && state.complete } ?: return
        val result = candidate.topaz ?: return
        if (state.openedProjectId != null) return
        val input = base?.document
        if (input == null || !stillHolds(input, state.request)) return stale()
        setCandidate(candidate.copy(topazPhase = TopazPhase.Opening))
        val mine = runSeq
        val copy = ++copySeq
        read {
            withContext(NonCancellable) {
                val created = topaz.host.createProject(result.file)
                // Still wanted only if nobody cancelled, closed, chose again or tapped again since.
                val wanted = mine == runSeq && copy == copySeq &&
                    _state.value.candidate?.topazPhase == TopazPhase.Opening
                when {
                    created is Result.Success && wanted -> _state.value = _state.value.copy(
                        candidate = _state.value.candidate?.copy(topazPhase = null),
                        openedProjectId = created.value,
                    )
                    created is Result.Success -> topaz.host.discardProject(created.value)
                    wanted -> _state.value = _state.value.copy(
                        candidate = _state.value.candidate?.copy(topazPhase = null),
                        message = DirectMessage(R.string.topaz_open_failed),
                    )
                }
            }
        }
    }

    /** The route navigated to the copy; this editor's sheet is done (D098). */
    fun onNavigated() {
        if (_state.value.openedProjectId == null) return
        _state.value = _state.value.copy(openedProjectId = null)
        topaz.host.onOpenedCopy()
    }

    /**
     * §19, D098 고해상도 저장: the reviewed Topaz result file, byte for byte, into the gallery. A retry
     * copies the same file again; it never calls Topaz.
     */
    fun save() {
        val state = _state.value
        val candidate = state.candidate?.takeIf { state.complete && it.topazPhase == null } ?: return
        val result = candidate.topaz ?: return
        setCandidate(candidate.copy(topazPhase = TopazPhase.Saving))
        val mine = runSeq
        read {
            val saved = topaz.host.saveToGallery(result.file, result.mimeType, result.width, result.height)
            val current = _state.value.candidate
            if (mine != runSeq || current?.topaz != result) return@read
            val ok = saved is Result.Success
            _state.value = _state.value.copy(
                candidate = current.copy(topazPhase = null, saved = current.saved || ok),
                message = DirectMessage(if (ok) R.string.topaz_saved else R.string.topaz_save_failed),
            )
        }
    }

    /** The proposal or the input moved on under the review: nothing is kept, and it says so. */
    private fun stale() {
        dropCandidate()
        proposals.invalidate()
        showMessage(R.string.direct_stale)
    }

    /** §18: the input is still on screen and the proposal it was asked from still stands. */
    private fun stillHolds(input: EditDocument, request: String): Boolean =
        host.canvas()?.document == input && proposals.readyFor(input, request) != null

    /**
     * §18 강도 줄이기: every numeric setting halfway back to this input, recomputed locally from
     * the same input — no server call, the same selections and generated patches. Halved down to
     * the input, there is nothing left to apply, which is said as such. §19: it acts on this
     * candidate only — an item that is off stays off, and a new choice rebuilds at full strength.
     * With the Topaz item on, the halved candidate is a new Topaz input: its result is this
     * session's for that input, or none until 선택한 보정 다시 실행.
     */
    // Nothing to halve, no input, nothing left: each is its own way out.
    @Suppress("ReturnCount")
    fun reduce() {
        val candidate = _state.value.candidate?.takeIf { it.phase == CandidatePhase.Review && it.reducible } ?: return
        val input = base?.document ?: return
        val document = candidate.document ?: return
        val reduced = reducedCandidate(input, document)
        // A selection alone changes no pixels; what is left after the halving decides.
        val structural = reduced.operations.filterNot { it is Operation.Adjust || it is Operation.Mask } !=
            input.operations.filterNot { it is Operation.Adjust || it is Operation.Mask }
        if (!hasNumericChange(input, reduced) && !structural) {
            dropCandidate()
            showMessage(R.string.direct_reduced_to_nothing)
            return
        }
        val mine = ++runSeq
        setCandidate(candidate.copy(phase = CandidatePhase.Checking))
        run = scope.launch {
            val findings = findings(input, reduced)
            if (mine != runSeq) return@launch
            setCandidate(
                candidate.copy(
                    phase = CandidatePhase.Review,
                    document = reduced,
                    reducible = hasNumericChange(input, reduced),
                    findings = findings,
                    comparing = false,
                    reduced = true,
                    topaz = _state.value.topazStep?.let { cachedTopaz(reduced, it) },
                    topazFailure = null,
                    saved = false,
                ),
            )
        }
    }

    /**
     * §18: the canvas shows the input while held (and, for TalkBack, while toggled on). §19: only a
     * complete result is compared — never the local half of a local + Topaz choice. Calls nothing.
     */
    fun setComparing(comparing: Boolean) {
        val candidate = _state.value.candidate?.takeIf { _state.value.complete } ?: return
        setCandidate(candidate.copy(comparing = comparing))
    }

    /**
     * §18 되돌리기 / 중단: the candidate goes; the proposal and the choice stay for another look,
     * and the session keeps what it made (§19). A selection waiting on the overlay is answered no.
     */
    fun discardCandidate() {
        when (_state.value.candidate?.phase) {
            null -> Unit
            // The run stops on the "no" itself, and says why.
            CandidatePhase.Confirming -> answerSelection(false)
            else -> dropCandidate()
        }
    }

    /** 취소, system back or another tool: the proposal and any candidate are discarded (§3). */
    fun close() {
        dropCandidate()
        proposals.invalidate()
        _state.value = _state.value.copy(
            request = "",
            requestSource = RequestSource.Typed,
            suggestions = _state.value.suggestions.copy(hidden = false),
        )
    }

    /**
     * DESIGN.md §7: the overlay's cancel button. A cancelled run commits nothing (§18) — the
     * document is as it was, and the proposal and its choice stay reviewable; a Topaz job in flight
     * is cancelled by the provider, best effort. A save stops; a copy already being created finishes
     * and is deleted (see [openCopy]); the result stays.
     */
    fun cancelWork() {
        val state = _state.value
        when {
            state.running -> {
                dropCandidate()
                showMessage(R.string.direct_cancelled)
            }
            state.keeping -> {
                if (state.candidate?.topazPhase == TopazPhase.Saving) readers.forEach { it.cancel() }
                copySeq++
                setCandidate(state.candidate?.copy(topazPhase = null))
            }
            else -> proposals.cancel()
        }
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = DirectMessage(res))
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun setCandidate(candidate: CandidateState?) {
        _state.value = _state.value.copy(candidate = candidate)
    }

    /**
     * §18: stops the run — the Topaz job with it — and a save, and answers a waiting overlay no.
     * Its files stay the session's (§19); a copy being created is deleted once it exists.
     */
    private fun dropCandidate() {
        runSeq++
        run?.cancel()
        run = null
        readers.forEach { it.cancel() }
        pendingConfirm?.complete(false)
        pendingConfirm = null
        _state.value = _state.value.copy(candidate = null)
    }

    /**
     * §19: the proposal session is over — applied, invalidated or closed. What it kept for reuse
     * goes, and so does every file it wrote except [keep], the applied document's. The Topaz folder
     * goes once nothing is reading it: a save is stopped first, a copy being created is waited for.
     */
    private fun endSession(keep: Set<String> = emptySet()) {
        val files = sessionFiles - keep
        sessionFiles.clear()
        cache.clear()
        runChecks.clear()
        topazResults.clear()
        base = null
        if (files.isNotEmpty()) scope.launch { withContext(NonCancellable) { host.discardFiles(files) } }
        val folder = topazFiles ?: return
        topazFiles = null
        val pending = readers.toList()
        scope.launch {
            withContext(NonCancellable) {
                pending.forEach { it.cancelAndJoin() }
                topaz.discard(folder)
            }
        }
    }

    /** Launches a reader of a Topaz result, tracked until it ends. */
    private fun read(block: suspend CoroutineScope.() -> Unit) {
        readers.removeAll { it.isCompleted }
        readers += scope.launch(block = block)
    }

    private fun endCandidate(message: DirectMessage) {
        _state.value = _state.value.copy(candidate = null, message = message)
    }

    /**
     * §10's last rows and §18's. A `Select` that found nothing, or found several things for a
     * step that asked for one, names the word it failed on — which is why `PlanRunner` puts the
     * phrase in the detail rather than inventing an `AppError`.
     */
    private fun messageOf(error: AppError): DirectMessage {
        val detail = (error as? AppError.Invalid)?.detail
        return when {
            detail == null -> DirectMessage(messageFor(error))
            detail.startsWith(PlanRunner.NOT_FOUND_PREFIX) ->
                DirectMessage(R.string.direct_not_found, detail.removePrefix(PlanRunner.NOT_FOUND_PREFIX))
            detail.startsWith(PlanRunner.AMBIGUOUS_PREFIX) ->
                DirectMessage(R.string.direct_ambiguous, detail.removePrefix(PlanRunner.AMBIGUOUS_PREFIX))
            detail == PlanRunner.SELECTION_REJECTED -> DirectMessage(R.string.direct_selection_rejected)
            else -> DirectMessage(messageFor(error))
        }
    }

    /** §6: a safety block is an `Invalid` with a recognizable prefix, not its own case. */
    @StringRes
    private fun messageFor(error: AppError): Int =
        if (error.isSafetyBlock()) {
            R.string.direct_blocked
        } else {
            R.string.direct_failed
        }

    private companion object {
        /** The selection's edge, 1/50 of its width, is not "outside" — masks are feathered. */
        const val EDGE_MARGIN_DIVISOR = 50

        val TONAL_KINDS = setOf(AdjustKind.Exposure, AdjustKind.Contrast, AdjustKind.Highlights, AdjustKind.Shadows)
    }
}

/** The document as rendered without its crop, so two frames line up pixel for pixel (§18). */
private fun EditDocument.withoutCrop(): EditDocument = copy(operations = operations.filterNot { it is Operation.Crop })

/**
 * §19: what a Topaz result was made from — the source and every operation the full render applies,
 * the item and the key's generation.
 */
private data class TopazKey(
    val source: ImageRef,
    val operations: List<Operation>,
    val step: PlanStep.Topaz,
    val generation: Long,
)

/**
 * The operations as rendered, without the ids a rebuild mints afresh for the same setting, crop or
 * cut-out — so the same choice off and on again is the same Topaz input. Masks and generated
 * patches keep theirs: the session reuses them by id, and other operations point at them by id.
 */
private fun EditDocument.renderedOperations(): List<Operation> = operations.map { op ->
    when (op) {
        is Operation.Adjust -> op.copy(id = "")
        is Operation.Crop -> op.copy(id = "")
        is Operation.CutOut -> op.copy(id = "")
        else -> op
    }
}
