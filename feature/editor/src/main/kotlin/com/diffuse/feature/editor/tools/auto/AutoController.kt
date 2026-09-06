package com.diffuse.feature.editor.tools.auto

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.AutoEnhanceProvider
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.ToolTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** specs/auto_enhance.md §6. The full-strength plan, and how much of it the user wants. */
const val AUTO_INTENSITY_MAX = 100

data class AutoState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val style: AutoStyle = AutoStyle.Balanced,
    /** The model's answer at full strength; the slider scales it without a second call. */
    val plan: Map<AdjustKind, Float> = emptyMap(),
    /** The model's own English sentence. §6: evidence, not copy. */
    val reason: String = "",
    val intensity: Int = AUTO_INTENSITY_MAX,
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready

    val canApply: Boolean get() = plan.isNotEmpty() && !busy

    /** §6: the slider is local, so 강도 costs no call — it scales what is already here. */
    fun scaled(): Map<AdjustKind, Float> = plan.mapValues { (kind, value) ->
        kind.coerce(value * intensity / AUTO_INTENSITY_MAX)
    }

    /**
     * §6: the plan applies **live** while the sheet is open, so 강도 is a slider on a result the
     * user is already looking at. The same fold is what 적용 commits, which is why the preview and
     * the history entry cannot drift apart.
     */
    fun appliedTo(document: EditDocument): EditDocument =
        scaled().entries.fold(document) { acc, (kind, value) ->
            acc.withAdjust(kind, value, maskId = null)
        }
}

/**
 * specs/auto_enhance.md §6. 자동 has **no sheet before the call** — tapping it runs, as 지우기
 * does — and a sheet after it, holding the result.
 *
 * What it commits is a plan of ordinary `Adjust` ops, so the boost is something the user can then
 * disagree with one slider at a time. That is ADR-015 reaching the UI.
 */
class AutoController(
    private val provider: AutoEnhanceProvider,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state.asStateFlow()

    private var job: Job? = null

    /** §6: changing the chip costs a call, and it is a call on the *same* photograph. */
    private var input: Bitmap? = null

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
        }
    }

    /**
     * §6's disabled-state table. A blank address and an unreachable server are told apart because
     * only the first is something the user can fix from the settings sheet.
     */
    fun onToolTapped(): ToolTap {
        val availability = _state.value.availability
        return when {
            availability is Availability.Ready -> ToolTap.Run
            (availability as? Availability.Unavailable)?.reason is AppError.Invalid -> {
                showMessage(R.string.auto_needs_server)
                ToolTap.OpenSettings
            }
            else -> {
                showMessage(R.string.auto_unreachable)
                ToolTap.Refused
            }
        }
    }

    /**
     * Runs the model and, on success, calls [onReady] so the sheet can open on a result rather
     * than on an empty box. A failure opens nothing and says why.
     */
    fun run(image: Bitmap?, style: AutoStyle = _state.value.style, onReady: () -> Unit) {
        if (image == null) {
            showMessage(R.string.auto_failed)
            return
        }
        job?.cancel()
        input = image
        _state.value = _state.value.copy(style = style, busy = true, message = null)
        job = scope.launch {
            when (val result = provider.enhance(image, style)) {
                is Result.Success -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        plan = result.value.adjustments,
                        reason = result.value.reason,
                        intensity = AUTO_INTENSITY_MAX,
                    )
                    onReady()
                }
                is Result.Failure -> _state.value =
                    _state.value.copy(busy = false, message = messageFor(result.error))
            }
        }
    }

    /**
     * §6: changing the chip re-runs. The sheet is already open, so nothing has to reopen it —
     * and the photograph is the one the first call was given, never the boosted preview the user
     * is currently looking at.
     */
    fun setStyle(style: AutoStyle) = run(input, style) {}

    fun setIntensity(intensity: Int) {
        _state.value = _state.value.copy(intensity = intensity.coerceIn(0, AUTO_INTENSITY_MAX))
    }

    /**
     * §6: 적용 commits **one** history entry holding every adjustment, so one undo removes the
     * whole boost. `masked = false` always — §9: MonetGPT has no regional edits, and the boost
     * never touches `activeMaskId`.
     */
    fun apply(document: EditDocument?): EditDocument? {
        val state = _state.value
        if (document == null || !state.canApply) return null
        return state.appliedTo(document)
    }

    /** DESIGN.md §7: cancelling leaves the document byte-for-byte untouched. */
    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    /** 취소, or a commit: the plan dies with the sheet. */
    fun close() {
        job?.cancel()
        input = null
        _state.value = _state.value.copy(
            plan = emptyMap(),
            reason = "",
            intensity = AUTO_INTENSITY_MAX,
            busy = false,
        )
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /** §6's last row. An unreachable server and a useless answer are different sentences. */
    @StringRes
    private fun messageFor(error: AppError): Int =
        if (error is AppError.Io || error == AppError.Unavailable) {
            R.string.auto_unreachable
        } else {
            R.string.auto_failed
        }
}
