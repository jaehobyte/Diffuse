package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EraseProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.feature.editor.tools.select.MaskOps
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** specs/vibe_edit.md §9. One event per thing the caller has to react to. */
sealed interface RunEvent {

    data class Started(val index: Int) : RunEvent

    /** [document] is the result of every step up to and including [index]. */
    data class Committed(val index: Int, val document: EditDocument) : RunEvent

    data class Stopped(val index: Int, val error: AppError) : RunEvent

    data object Completed : RunEvent
}

/**
 * specs/vibe_edit.md §9. Runs a plan against the providers the manual tools use. It adds no
 * imaging code: every step is a call one of the tools already makes.
 *
 * It takes save lambdas rather than `ProjectRepository` because `projectId` lives in
 * `EditorViewModel`'s `SavedStateHandle`, the shape `EraseController` already uses. And it emits
 * events rather than pushing history, because only `EditorViewModel` holds the `HistoryStack`.
 */
class PlanRunner(
    private val segmentation: SegmentationProvider,
    private val erase: EraseProvider,
    private val dispatchers: DispatcherProvider,
    /** `repository.saveMask(projectId, …)`, bound by the ViewModel. */
    private val saveMask: suspend (String, Bitmap) -> Result<ImageRef>,
    /** `repository.saveEraseResult(projectId, …)`, likewise. */
    private val saveEraseResult: suspend (String, Bitmap) -> Result<ImageRef>,
) {

    /**
     * §9.1: a step that consumes a selection must have one, from an earlier `Select` in the same
     * plan or from the document. Checked when the plan arrives, so a plan that cannot work is
     * never shown as if it could.
     *
     * @return the first step that cannot run, or null.
     */
    fun validate(plan: EditPlan, document: EditDocument): PlanStep? {
        var selected = document.activeMaskId != null
        return plan.steps.firstOrNull { step ->
            val missing = step.consumesSelection && !selected
            if (step is PlanStep.Select) selected = true
            missing
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
     */
    fun run(
        plan: EditPlan,
        document: EditDocument,
        preview: Bitmap,
        activeMask: Bitmap?,
    ): Flow<RunEvent> = flow {
        val run = Run(preview, activeMask)
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

    /** One run's mutable parts: the session it opened and the mask the steps share. */
    private inner class Run(private val preview: Bitmap, private var mask: Bitmap?) {

        private var session: SegSession? = null

        suspend fun apply(step: PlanStep, document: EditDocument): Result<EditDocument> =
            when (step) {
                is PlanStep.Select -> select(step.phrase, document)
                is PlanStep.Adjust -> Result.Success(
                    document.withAdjust(
                        step.kind,
                        step.value,
                        if (step.masked) document.activeMaskId else null,
                    ),
                )
                PlanStep.Erase -> eraseSelection(document)
                PlanStep.CutOut -> cutOut(document)
            }

        /** §9.2: the session is opened once, on the current preview, and closed with the run. */
        suspend fun close() {
            session?.let { withContext(NonCancellable) { segmentation.close(it) } }
            session = null
        }

        private suspend fun select(phrase: String, document: EditDocument): Result<EditDocument> =
            when (val opened = session()) {
                is Result.Failure -> opened
                is Result.Success -> segment(opened.value, phrase, document)
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
         * §9.2: a phrase's instances union into one mask, as in the manual tool. An empty result
         * **stops the run** — the steps after it were written for a selection that does not
         * exist — and names the phrase that failed.
         */
        private suspend fun segment(
            session: SegSession,
            phrase: String,
            document: EditDocument,
        ): Result<EditDocument> = when (val found = segmentation.byText(session, phrase)) {
            is Result.Failure -> found
            is Result.Success -> {
                val union = MaskOps.union(found.value.map { it.alpha })
                if (union == null) {
                    Result.Failure(AppError.Invalid("$NOT_FOUND_PREFIX$phrase"))
                } else {
                    storeMask(union, document)
                }
            }
        }

        private suspend fun storeMask(
            union: Bitmap,
            document: EditDocument,
        ): Result<EditDocument> {
            val maskId = newId()
            return when (val saved = saveMask(maskId, union)) {
                is Result.Failure -> saved
                is Result.Success -> {
                    mask = union
                    Result.Success(document.withMask(saved.value, maskId))
                }
            }
        }

        private suspend fun eraseSelection(document: EditDocument): Result<EditDocument> {
            val maskId = document.activeMaskId
            val selected = mask
            return if (maskId == null || selected == null) {
                missing()
            } else {
                when (val result = erase.erase(preview, selected, hint = null)) {
                    is Result.Failure -> result
                    is Result.Success -> storeErase(maskId, result.value, document)
                }
            }
        }

        private fun cutOut(document: EditDocument): Result<EditDocument> {
            val maskId = document.activeMaskId
            return if (maskId == null) missing() else Result.Success(document.withCutOut(maskId))
        }

        private suspend fun storeErase(
            maskId: String,
            result: Bitmap,
            document: EditDocument,
        ): Result<EditDocument> {
            val eraseId = newId()
            return when (val saved = saveEraseResult(eraseId, result)) {
                is Result.Failure -> saved
                is Result.Success ->
                    Result.Success(document.withGenerativeErase(maskId, saved.value, eraseId))
            }
        }

        /**
         * `validate` has already refused a plan whose selection is missing, so reaching this
         * means the plan was never validated. It fails rather than quietly editing the whole
         * photo, which is the choice §9.1 argues for.
         */
        private fun missing(): Result<EditDocument> =
            Result.Failure(AppError.Invalid("no selection"))
    }

    companion object {
        /**
         * §10: "a `Select` step found nothing" is its own message, and it names the word. A
         * detail prefix rather than a new `AppError`, the shape `blocked:` already uses.
         */
        const val NOT_FOUND_PREFIX = "not found:"
    }
}

/** §9.1: `Select` produces a selection; these three consume one. */
private val PlanStep.consumesSelection: Boolean
    get() = when (this) {
        is PlanStep.Select -> false
        is PlanStep.Adjust -> masked
        PlanStep.Erase, PlanStep.CutOut -> true
    }
