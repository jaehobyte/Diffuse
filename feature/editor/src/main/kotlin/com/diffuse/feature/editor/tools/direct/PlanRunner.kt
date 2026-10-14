package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EraseProvider
import com.diffuse.core.ai.FillProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.ai.StyleId
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.feature.editor.tools.crop.CropState
import com.diffuse.feature.editor.tools.crop.preset
import com.diffuse.feature.editor.tools.erase.EraseCommit
import com.diffuse.feature.editor.tools.erase.EraseMask
import com.diffuse.feature.editor.tools.fill.FillCommit
import com.diffuse.feature.editor.tools.fill.FillMask
import com.diffuse.feature.editor.tools.select.MaskOps
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * specs/vibe_edit.md §9, §18. One event per thing the caller has to react to. A run builds a
 * **candidate**: [Committed] is a step added to it, not a history entry — the caller commits the
 * reviewed candidate once, or discards it.
 */
sealed interface RunEvent {

    data class Started(val index: Int) : RunEvent

    /** [document] is the candidate after every step up to and including [index]. */
    data class Committed(val index: Int, val document: EditDocument) : RunEvent

    data class Stopped(val index: Int, val error: AppError) : RunEvent

    data object Completed : RunEvent
}

/**
 * specs/vibe_edit.md §15, §18, §19. A selection a step is about to use and what it means: a new one
 * the segmenter found for the plan's [phrase] — used straight away and reported, never asked about —
 * or an existing one whose meaning this session does not know ([phrase] null), which the user is
 * asked to confirm on an overlay first.
 */
data class TargetCheck(val phrase: String?, val mask: Bitmap, val maskId: String)

/**
 * specs/vibe_edit.md §9. Runs a plan against the providers the manual tools use. It adds no
 * imaging code: every step is a call one of the tools already makes.
 *
 * It takes save lambdas rather than `ProjectRepository` because `projectId` lives in
 * `EditorViewModel`'s `SavedStateHandle`, the shape `EraseController` already uses. And it emits
 * events rather than pushing history, because only `EditorViewModel` holds the `HistoryStack`.
 */
// The eighth parameter is §18's overlay mask; the seventh is specs/vibe_edit.md §9's own
// signature: three providers, a dispatcher
// and one writer per thing a step can produce. Grouping the writers would hide which step writes
// what, which is the only reason this class takes lambdas rather than the repository.
@Suppress("LongParameterList")
class PlanRunner(
    private val segmentation: SegmentationProvider,
    private val erase: EraseProvider,
    /** T62: a `Fill` step is the only one that uses it (specs/generative_fill.md §8). */
    private val fill: FillProvider,
    private val dispatchers: DispatcherProvider,
    /** `repository.saveMask(projectId, …)`, bound by the ViewModel. */
    private val saveMask: suspend (String, Bitmap) -> Result<ImageRef>,
    /** T67: 채우기's rectangle and its result, committed the way the tool commits them. */
    private val fillCommit: FillCommit,
    /** The 지우기 tool's commit, shared so both paths erase through the same margin (T50). */
    private val eraseCommit: EraseCommit,
    /**
     * T70: the frame a generative step is shown — the step's own document minus its `Adjust` ops,
     * which is `tools/AdjustStack.kt`'s `generativeInput` bound by the ViewModel. The run's
     * `preview` is what the *user* is looking at and stays the segmentation session's; a baked
     * adjustment in a generated result could never be re-dragged, so these two frames differ on
     * purpose.
     */
    private val generativeInput: suspend (EditDocument) -> Bitmap?,
    /**
     * specs/style_match.md §6: a catalogued id and a strength become `AdjustKind` values. The
     * catalog is an asset the ViewModel can reach and this class cannot, so it arrives as a
     * lambda — the shape every other thing `PlanRunner` cannot know already takes.
     */
    private val styleParams: suspend (StyleId, Int) -> Map<AdjustKind, Float>,
    /**
     * §18: an existing selection's mask at preview size, to show on the confirmation overlay —
     * `Renderer.resolveMask`, bound by the ViewModel.
     */
    private val resolveMask: suspend (EditDocument, String) -> Bitmap? = { _, _ -> null },
) {

    /**
     * §9.1, §15: every target a step names must exist when it runs — an existing one in
     * [document], a new one declared by an earlier `Select` — and erase, cut-out and fill act on
     * the selection made right before them. Checked when the plan arrives, so a plan that cannot
     * work is never shown as if it could. A `Topaz` step is never run here (§16), and is valid.
     *
     * @return the first step that cannot run, or null.
     */
    fun validate(plan: EditPlan, document: EditDocument): PlanStep? {
        val declared = mutableSetOf<String>()
        var current: String? = null
        return plan.steps.firstOrNull { step ->
            val invalid = when (step) {
                is PlanStep.Select -> !declared.add(step.label)
                is PlanStep.Adjust -> when (val target = step.target) {
                    null -> false
                    is PlanTarget.Existing -> document.mask(target.maskId) == null
                    is PlanTarget.New -> target.label !in declared
                }
                is PlanStep.Erase -> step.target != current
                is PlanStep.CutOut -> step.target != current
                is PlanStep.Fill -> step.target != current
                is PlanStep.Crop, is PlanStep.Style, is PlanStep.Topaz -> false
            }
            if (step is PlanStep.Select) current = step.label
            invalid
        }
    }

    /**
     * §9.2, §9.3. Each step is chained onto the previous step's document; a failure or a
     * cancellation ends the run with everything before it already emitted, and the in-flight
     * step commits nothing.
     *
     * @param preview what the canvas is showing; the session and the eraser both work on it.
     * @param activeMask the document's applied mask, already resolved by the caller. A `Select`
     * step replaces it for the steps that follow.
     * @param sourceAspect width ÷ height of the **un-cropped** source, which is what a `Crop`
     * step's rect is normalised against (specs/crop.md). The preview cannot supply it: once the
     * document carries a crop, the preview is the cropped shape.
     */
    // The run's inputs are the canvas's five facts plus §18's confirmation gate and §19's report.
    @Suppress("LongParameterList")
    fun run(
        plan: EditPlan,
        document: EditDocument,
        preview: Bitmap,
        activeMask: Bitmap?,
        sourceAspect: Float,
        /**
         * §18: an existing selection of unknown meaning shown on an overlay; false stops the run
         * before anything uses it. Not asked for one in [confirmed].
         */
        confirm: suspend (TargetCheck) -> Boolean = { true },
        /** Mask ids whose meaning this session knows — confirmed or found for a phrase (§15). */
        confirmed: Set<String> = emptySet(),
        /** §19: this proposal session's selections and generated results, reused. */
        cache: PlanCache? = null,
        /**
         * §19: a rebuild after a toggle. Nothing remote is called: a selection or a generated
         * result the [cache] does not hold stops the run with [NEEDS_PREVIEW] instead.
         */
        offline: Boolean = false,
        /**
         * §19: a new selection the segmenter found for its phrase and the checks passed, stored and
         * about to be used without asking — so the caller can say so and record it as found, not
         * as confirmed.
         */
        onResolved: (TargetCheck) -> Unit = {},
    ): Flow<RunEvent> = flow {
        val run = Run(preview, activeMask, sourceAspect, confirm, confirmed, cache, offline, onResolved)
        var current = document
        var stopped = false
        try {
            plan.steps.forEachIndexed { index, step ->
                if (!stopped) {
                    emit(RunEvent.Started(index))
                    when (val outcome = run.apply(step, current)) {
                        is Result.Success -> {
                            current = outcome.value
                            emit(RunEvent.Committed(index, current))
                        }
                        is Result.Failure -> {
                            emit(RunEvent.Stopped(index, outcome.error))
                            stopped = true
                        }
                    }
                }
            }
            if (!stopped) emit(RunEvent.Completed)
        } finally {
            run.close()
        }
    }.flowOn(dispatchers.io)

    /**
     * One run's mutable parts: the session it opened, the selections its `Select` steps made by
     * label, and the existing selections the user confirmed during it.
     */
    // The run's own state is what the steps share; each field is one of §15's facts.
    @Suppress("LongParameterList")
    private inner class Run(
        private val preview: Bitmap,
        private var mask: Bitmap?,
        private val sourceAspect: Float,
        private val confirm: suspend (TargetCheck) -> Boolean,
        confirmed: Set<String>,
        private val cache: PlanCache?,
        private val offline: Boolean,
        private val onResolved: (TargetCheck) -> Unit,
    ) {

        private var session: SegSession? = null

        /** T51: what the last `Select` was looking for, so the eraser can be told what is gone. */
        private var phrase: String? = null

        /** §15: label → the mask id its `Select` stored. */
        private val labels = mutableMapOf<String, String>()
        private val confirmedIds = confirmed.toMutableSet()

        suspend fun apply(step: PlanStep, document: EditDocument): Result<EditDocument> =
            when (step) {
                is PlanStep.Select -> {
                    phrase = step.phrase
                    select(step, document)
                }
                is PlanStep.Adjust -> adjust(step, document)
                // §15: these act on the selection made right before them, by name — never on
                // whatever else happens to be active.
                is PlanStep.Erase -> if (isCurrent(step.target, document)) {
                    generated(step, document) { eraseSelection(document) }
                } else {
                    missing()
                }
                is PlanStep.Fill -> if (isCurrent(step.target, document)) {
                    generated(step, document) { fillSelection(step.prompt, document) }
                } else {
                    missing()
                }
                is PlanStep.CutOut -> if (isCurrent(step.target, document)) cutOut(document) else missing()
                is PlanStep.Crop -> Result.Success(crop(step, document))
                is PlanStep.Style -> Result.Success(style(step, document))
                // §16: a Topaz step opens its sheet; it is never run here.
                is PlanStep.Topaz -> Result.Failure(AppError.Unsupported)
            }

        private fun isCurrent(label: String, document: EditDocument): Boolean =
            labels[label] != null && labels[label] == document.activeMaskId

        /**
         * §15: the target is the one the step names — an existing selection's own mask id (its
         * setting updated in place), or the mask a `Select` in this run stored for the label.
         * An existing selection whose meaning was never confirmed is shown first (§18).
         */
        private suspend fun adjust(step: PlanStep.Adjust, document: EditDocument): Result<EditDocument> {
            val maskId = when (val target = step.target) {
                null -> null
                is PlanTarget.New -> labels[target.label] ?: return missing()
                is PlanTarget.Existing -> target.maskId
            }
            val confirmed = maskId == null || maskId in confirmedIds || maskId in labels.values ||
                confirmExisting(document, maskId)
            return if (confirmed) Result.Success(document.withAdjust(step.kind, step.value, maskId)) else rejected()
        }

        /** §18: an existing selection's meaning, asked once per run on the overlay. */
        private suspend fun confirmExisting(document: EditDocument, maskId: String): Boolean {
            val shown = resolveMask(document, maskId) ?: return false
            return confirm(TargetCheck(null, shown, maskId)).also { if (it) confirmedIds += maskId }
        }

        /** §9.2: the session is opened once, on the current preview, and closed with the run. */
        suspend fun close() {
            session?.let { withContext(NonCancellable) { segmentation.close(it) } }
            session = null
        }

        /**
         * §19: a selection this proposal session already made is reused — same frame, same
         * phrase — with no segmenter call.
         */
        // A hit, an offline miss and a segmenter call are three different answers.
        @Suppress("ReturnCount")
        private suspend fun select(step: PlanStep.Select, document: EditDocument): Result<EditDocument> {
            val hit = cache?.selection(step)
            if (hit != null) {
                mask = hit.mask
                labels[step.label] = hit.maskId
                return Result.Success(document.withMask(hit.ref, hit.maskId))
            }
            if (offline) return needsPreview()
            return when (val opened = session()) {
                is Result.Failure -> opened
                is Result.Success -> segment(opened.value, step, document)
            }
        }

        /**
         * §19: a generated patch is reused only for exactly the input it was made from — the
         * frame without its adjustments (what the provider was shown), the selection it consumed
         * and the step's own options. Anything else is a new paid call, which an offline rebuild
         * never makes.
         */
        // A hit, an offline miss and a call are three different answers.
        @Suppress("ReturnCount")
        private suspend fun generated(
            step: PlanStep,
            document: EditDocument,
            call: suspend () -> Result<EditDocument>,
        ): Result<EditDocument> {
            val key = PlanCache.Key(step, phrase, document)
            cache?.generated(key)?.let { added -> return Result.Success(PlanCache.reapply(document, added)) }
            if (offline) return needsPreview()
            return call().also { result ->
                if (result is Result.Success) cache?.putGenerated(key, PlanCache.added(document, result.value))
            }
        }

        private suspend fun session(): Result<SegSession> {
            val existing = session
            return if (existing != null) {
                Result.Success(existing)
            } else {
                segmentation.open(preview).also {
                    if (it is Result.Success) session = it.value
                }
            }
        }

        /**
         * §9.2, §18, §19: what the segmenter answered is checked before anything uses it. No
         * instance with any pixel set **stops the run** and names the phrase; a mask that is not
         * the preview's size stops it too. Several instances union only when the step asked for
         * several things — one person is never silently everyone found. A selection that passes
         * is the plan's explicit target resolved: it is stored and used without an overlay, and
         * reported through `onResolved` so the review can name it.
         */
        private suspend fun segment(
            session: SegSession,
            step: PlanStep.Select,
            document: EditDocument,
        ): Result<EditDocument> {
            val found = when (val answered = segmentation.byText(session, step.phrase)) {
                is Result.Failure -> return answered
                is Result.Success -> answered.value.map { it.alpha }.filter(::anySet)
            }
            return when {
                found.isEmpty() -> Result.Failure(AppError.Invalid("$NOT_FOUND_PREFIX${step.phrase}"))
                found.any { it.width != preview.width || it.height != preview.height } ->
                    Result.Failure(AppError.Invalid(MASK_SIZE))
                found.size > 1 && !step.multiple -> Result.Failure(AppError.Invalid("$AMBIGUOUS_PREFIX${step.phrase}"))
                else -> {
                    val union = requireNotNull(MaskOps.union(found))
                    val maskId = newId()
                    storeMask(union, maskId, document).also { stored ->
                        if (stored is Result.Success) {
                            labels[step.label] = maskId
                            stored.value.mask(maskId)?.let { cache?.putSelection(step, maskId, it.maskRef, union) }
                            onResolved(TargetCheck(step.phrase, union, maskId))
                        }
                    }
                }
            }
        }

        private suspend fun storeMask(
            union: Bitmap,
            maskId: String,
            document: EditDocument,
        ): Result<EditDocument> = when (val saved = saveMask(maskId, union)) {
            is Result.Failure -> saved
            is Result.Success -> {
                mask = union
                Result.Success(document.withMask(saved.value, maskId))
            }
        }

        private suspend fun eraseSelection(document: EditDocument): Result<EditDocument> {
            val selected = mask
            val frame = generativeInput(document)
            return if (document.activeMaskId == null || selected == null || frame == null) {
                missing()
            } else {
                val dilated = EraseMask.dilated(selected)
                when (val result = erase.erase(frame, dilated, hint = phrase)) {
                    is Result.Failure -> result
                    is Result.Success -> eraseCommit.apply(document, dilated, result.value)
                }
            }
        }

        /**
         * §9.2, T67: the selection's **bounding box with a margin**, exactly as the 채우기 tool
         * sends it. 지우기 dilates the silhouette because it is reconstructing what was behind a
         * thing; 채우기 replaces the thing, and a silhouette would dictate the new one's shape.
         *
         * T70: and on the same frame the tool sends, so a plan and a tap agree (T53's property).
         */
        private suspend fun fillSelection(
            prompt: String,
            document: EditDocument,
        ): Result<EditDocument> {
            val rectangle = mask?.let(FillMask::rectangle)
            val frame = generativeInput(document)
            return if (document.activeMaskId == null || rectangle == null || frame == null) {
                missing()
            } else {
                when (val result = fill.fill(frame, rectangle, prompt)) {
                    is Result.Failure -> result
                    is Result.Success ->
                        fillCommit.apply(document, rectangle, prompt, result.value)
                }
            }
        }

        /**
         * §4.1: the largest centred rect at the requested ratio, through the **same**
         * `CropGeometry.applyPreset` a tap on the chip takes. No geometry is written here.
         */
        private fun crop(step: PlanStep.Crop, document: EditDocument): EditDocument =
            CropState.from(document, sourceAspect).withPreset(step.ratio.preset).applyTo(document)

        /**
         * specs/style_match.md §6: the id resolves to a preset here, at the `feature:editor`
         * boundary `CropRatio` already crosses, and the preset becomes ordinary `Adjust` ops —
         * exactly what the 스타일 sheet's 적용 commits. An id the catalog does not have leaves the
         * document alone; `apply_style`'s enum is closed, so that means the catalog moved.
         */
        private suspend fun style(step: PlanStep.Style, document: EditDocument): EditDocument =
            styleParams(step.style, step.intensity)
                .entries
                .fold(document) { acc, (kind, value) -> acc.withAdjust(kind, value, maskId = null) }

        private fun cutOut(document: EditDocument): Result<EditDocument> {
            val maskId = document.activeMaskId
            return if (maskId == null) missing() else Result.Success(document.withCutOut(maskId))
        }

        /**
         * `validate` has already refused a plan whose selection is missing, so reaching this
         * means the plan was never validated. It fails rather than quietly editing the whole
         * photo, which is the choice §9.1 argues for.
         */
        private fun missing(): Result<EditDocument> =
            Result.Failure(AppError.Invalid("no selection"))

        private fun rejected(): Result<EditDocument> = Result.Failure(AppError.Invalid(SELECTION_REJECTED))

        private fun needsPreview(): Result<EditDocument> = Result.Failure(AppError.Invalid(NEEDS_PREVIEW))
    }

    companion object {
        /**
         * §10: "a `Select` step found nothing" is its own message, and it names the word. A
         * detail prefix rather than a new `AppError`, the shape `blocked:` already uses.
         */
        const val NOT_FOUND_PREFIX = "not found:"

        /** §18: several instances for a step that asked for one thing; names the phrase. */
        const val AMBIGUOUS_PREFIX = "ambiguous:"

        /** §18: a segmenter answer whose size is not the frame's. */
        const val MASK_SIZE = "mask size"

        /** §18: the user said the existing selection on the overlay is not what was meant. */
        const val SELECTION_REJECTED = "selection rejected"

        /** §19: an offline rebuild reached a step only a remote call can answer. */
        const val NEEDS_PREVIEW = "needs preview"
    }
}

/** §18: an instance with no pixel set is no selection at all (an all-zero alpha). */
private fun anySet(mask: Bitmap): Boolean {
    val pixels = IntArray(mask.width * mask.height)
    mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
    return pixels.any { it ushr ALPHA_SHIFT != 0 }
}

private const val ALPHA_SHIFT = 24
