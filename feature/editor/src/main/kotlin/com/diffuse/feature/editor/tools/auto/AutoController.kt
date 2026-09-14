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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** specs/auto_enhance.md §6. The full-strength plan, and how much of it the user wants. */
const val AUTO_INTENSITY_MAX = 100

data class AutoState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    /**
     * §6: a probe is in flight, so [availability] is not yet the answer for the current settings.
     * True until the provider first says otherwise: a tap in that window is "checking", never
     * "unreachable".
     */
    val checking: Boolean = true,
    val style: AutoStyle = AutoStyle.Balanced,
    /** The model's answer at full strength; the slider scales it without a second call. */
    val plan: Map<AdjustKind, Float> = emptyMap(),
    /** The model's own English sentence. §6: evidence, not copy. */
    val reason: String = "",
    val intensity: Int = AUTO_INTENSITY_MAX,
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready && !checking

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
    /** Every 서버 설정 save. A run in flight was asked of the settings it replaced. */
    settingsSaves: Flow<Int> = emptyFlow(),
) {

    private val _state = MutableStateFlow(AutoState())
    val state: StateFlow<AutoState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Which run may still write. A provider that ignores cancellation can return after [cancel],
     * [close], a newer chip or a settings change; its result and its `busy = false` are then for
     * a run nobody is waiting on, and must not reach the plan, the preview or the newer run's
     * spinner.
     */
    private var runId = 0

    /** A re-check the user asked for by tapping: its answer is worth a snackbar. */
    private var recheckRequested = false

    /** §6: changing the chip costs a call, and it is a call on the *same* photograph. */
    private var input: Bitmap? = null

    init {
        scope.launch {
            val probes = combine(provider.availability, provider.checking, ::Pair)
            probes.collect { (availability, checking) ->
                val answered = recheckRequested && !checking
                if (answered) recheckRequested = false
                _state.value = _state.value.copy(
                    availability = availability,
                    checking = checking,
                    message = if (answered) answerMessage(availability) else _state.value.message,
                )
            }
        }
        scope.launch {
            // The current value is not a change; only a later save is.
            settingsSaves.drop(1).collect { invalidateRun() }
        }
    }

    /**
     * §6's disabled-state table. Each reason goes where it can be fixed: an address or a token to
     * the 서버 설정 sheet, a server that is loading or unreachable to a re-check the user can see
     * **and** the sheet, since only the user knows whether the address itself has moved.
     * The tap **is** the retry — nothing re-probes on a timer.
     */
    fun onToolTapped(): ToolTap {
        val current = _state.value
        val reason = (current.availability as? Availability.Unavailable)?.reason
        return when {
            current.checking -> refuse(R.string.auto_checking)
            current.availability is Availability.Ready -> ToolTap.Run
            reason is AppError.Invalid && reason.detail == AutoEnhanceProvider.NO_SERVER ->
                openSettings(R.string.auto_needs_server)
            reason is AppError.Invalid -> openSettings(R.string.auto_invalid_address)
            reason == AppError.Unauthorized -> openSettings(R.string.auto_unauthorized)
            else -> {
                // Whether this server answered earlier says nothing about the address now: the
                // server may have moved, the network changed or a USB reverse dropped. So the
                // sheet opens beside the re-check every time — the user can save or close it.
                val tap = openSettings(R.string.auto_rechecking)
                // After the message, so an answer that lands at once replaces it, not the reverse.
                recheck()
                tap
            }
        }
    }

    private fun recheck() {
        recheckRequested = true
        provider.refresh()
    }

    private fun refuse(@StringRes res: Int): ToolTap {
        showMessage(res)
        return ToolTap.Refused
    }

    private fun openSettings(@StringRes res: Int): ToolTap {
        showMessage(res)
        return ToolTap.OpenSettings
    }

    /** What a re-check the user asked for came back with. */
    @StringRes
    private fun answerMessage(availability: Availability): Int {
        val reason = (availability as? Availability.Unavailable)?.reason
        return when {
            availability is Availability.Ready -> R.string.auto_ready
            reason is AppError.Invalid && reason.detail == AutoEnhanceProvider.NO_SERVER ->
                R.string.auto_needs_server
            reason is AppError.Invalid -> R.string.auto_invalid_address
            reason == AppError.Unauthorized -> R.string.auto_unauthorized
            reason == AppError.Unavailable -> R.string.auto_not_ready
            else -> R.string.auto_unreachable
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
        val id = ++runId
        input = image
        _state.value = _state.value.copy(style = style, busy = true, message = null)
        job = scope.launch {
            val result = provider.enhance(image, style)
            if (id != runId) return@launch
            when (result) {
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
    fun cancel() = invalidateRun()

    /**
     * The session — the input a chip re-runs on, the plan 적용 commits, a run in flight — belongs
     * to the previous document; none of it may land on this one. Ends the session and returns
     * whether there was one, so the host can close the sheet.
     */
    fun onDocumentChanged(): Boolean {
        if (input == null) return false
        close()
        return true
    }

    private fun invalidateRun() {
        runId++
        job?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    /** 취소, or a commit: the plan dies with the sheet. */
    fun close() {
        runId++
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
    private fun messageFor(error: AppError): Int = when {
        error is AppError.Io -> R.string.auto_unreachable
        error == AppError.Unavailable -> R.string.auto_not_ready
        error == AppError.Unauthorized -> R.string.auto_unauthorized
        else -> R.string.auto_failed
    }
}
