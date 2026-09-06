package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** specs/vibe_edit.md §3. Sheet state; nothing here reaches the document until 적용. */
data class DirectState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val request: String = "",
    /** The plan waiting for 적용, or null. It is never persisted (§13). */
    val plan: EditPlan? = null,
    val planning: Boolean = false,
    val running: Boolean = false,
    /**
     * §10: an empty plan and one `validate` rejected are the same event to the user — a hint
     * under the bar, never a snackbar and never a dialog.
     */
    val notUnderstood: Boolean = false,
    val message: DirectMessage? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready

    /** DESIGN.md §7: both the planning call and the run show progress and a way out. */
    val working: Boolean get() = planning || running

    val canApply: Boolean get() = plan != null && !working
}

/**
 * specs/vibe_edit.md §3, §10. Owns the plan between the response and 적용. Running it is
 * `EditorViewModel`'s job, because only it holds the `HistoryStack` — the split the selection
 * and erase tools already use.
 */
class DirectController(
    private val provider: EditPlanProvider,
    private val runner: PlanRunner,
    private val scope: CoroutineScope,
    private val host: DirectHost,
) {

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
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

    fun setRequest(request: String) {
        _state.value = _state.value.copy(request = request)
    }

    /**
     * §3: submitting asks for a plan, and a second phrase replaces the first — nothing has been
     * applied yet, so there is nothing to undo. §9.1 validates it before it is ever shown.
     */
    fun submit(request: String) {
        val canvas = host.canvas()
        if (request.isBlank() || canvas == null) return
        job?.cancel()
        _state.value = _state.value.copy(
            request = request,
            plan = null,
            planning = true,
            notUnderstood = false,
            message = null,
        )
        job = scope.launch {
            _state.value = when (val result = provider.plan(canvas.preview, request)) {
                is Result.Success -> accept(result.value, canvas.document)
                is Result.Failure -> _state.value.copy(
                    planning = false,
                    message = DirectMessage(messageFor(result.error)),
                )
            }
        }
    }

    /**
     * §5, §9.1: an empty plan is a valid answer and a rejected one is the same event to the
     * user. Both leave 적용 disabled and show the hint.
     */
    private fun accept(plan: EditPlan, document: EditDocument): DirectState {
        val usable = plan.steps.isNotEmpty() && runner.validate(plan, document) == null
        return _state.value.copy(
            planning = false,
            plan = plan.takeIf { usable },
            notUnderstood = !usable,
        )
    }

    /**
     * §9.2, §9.3: 적용 runs the plan, one history entry per step and no coalesce key, and the
     * sheet closes when the run ends.
     */
    fun apply() {
        val plan = _state.value.plan ?: return
        val canvas = host.canvas() ?: return
        _state.value = _state.value.copy(running = true, message = null)
        job = scope.launch {
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

    /** §9.3: the run ended, whichever way. The plan dies with the sheet either way (§13). */
    private fun onRunEnded(error: AppError?) {
        _state.value = _state.value.copy(
            running = false,
            plan = null,
            request = "",
            notUnderstood = false,
            message = error?.let(::messageOf),
        )
    }

    /** 취소 or system back: the plan is discarded and the document was never touched (§3). */
    fun close() {
        job?.cancel()
        _state.value = _state.value.copy(
            request = "",
            plan = null,
            planning = false,
            running = false,
            notUnderstood = false,
        )
    }

    /** DESIGN.md §7: the overlay's cancel button. */
    fun cancelWork() {
        job?.cancel()
        _state.value = _state.value.copy(planning = false, running = false)
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
