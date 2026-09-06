package com.diffuse.feature.editor.tools.expand

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.OutpaintProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Margins
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.diffuse.core.ai.Margins as RequestMargins

/**
 * specs/outpaint.md §6. What tapping the tool should do, returned rather than acted on: the
 * 서버 설정 sheet is `SelectionController`'s, and there is one of it (generative_erase.md §9).
 */
enum class ExpandTap {
    /** Open the sheet and the overlay, which is where the margins come from. */
    Open,

    /** The key is blank, which only the settings sheet can fix. */
    OpenSettings,

    /** The reason is already in [ExpandState.message]; nothing more to offer. */
    Refused,
}

/** specs/outpaint.md §6. Sheet state; nothing here reaches the document until 적용. */
data class ExpandState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    /** The pending margins, drawn by the overlay and sent by 적용. */
    val margins: Margins = Margins.None,
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready

    /** §6: 적용 is disabled while every margin is 0 — there is nothing to invent. */
    val canApply: Boolean get() = !margins.isEmpty && !busy
}

/**
 * Runs the outpaint and hands the result back; committing it is `EditorViewModel`'s job, because
 * only it owns the history stack — the same split 지우기 and 채우기 use.
 *
 * The image it sends is the **bare source**, not the current preview (§3): a second 확대 recomputes
 * from the photograph at the new margins rather than extending the model's previous invention, so
 * margins never compound and quality never degrades by iteration.
 */
class ExpandController(
    private val provider: OutpaintProvider,
    private val saveResult: suspend (outpaintId: String, result: Bitmap) -> Result<ImageRef>,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ExpandState())
    val state: StateFlow<ExpandState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
        }
    }

    /**
     * §6's disabled-state table, in its order: the mask-op guard is reported before a missing
     * key, because that one is about the document rather than about the setup.
     */
    fun onToolTapped(canOutpaint: Boolean): ExpandTap {
        val availability = _state.value.availability
        return when {
            !canOutpaint -> {
                showMessage(R.string.expand_after_mask)
                ExpandTap.Refused
            }
            availability is Availability.Ready -> ExpandTap.Open
            // §5: availability is derived from the key with no probe, so `Invalid` here is the
            // "no key" row rather than a general outage.
            (availability as? Availability.Unavailable)?.reason is AppError.Invalid -> {
                showMessage(R.string.expand_needs_key)
                ExpandTap.OpenSettings
            }
            else -> {
                showMessage(R.string.expand_failed)
                ExpandTap.Refused
            }
        }
    }

    fun setMargins(margins: Margins) {
        _state.value = _state.value.copy(margins = margins)
    }

    /**
     * §6: 적용 runs the model and, only once the answer is on disk, hands the new document to
     * [onCommitted] as one history entry. On failure the sheet stays open with the margins
     * intact, so a retry costs no dragging.
     */
    fun runAndCommit(
        source: Bitmap?,
        document: EditDocument?,
        onCommitted: (EditDocument) -> Unit,
    ) {
        val margins = _state.value.margins
        if (source == null || document == null || margins.isEmpty) return
        job?.cancel()
        _state.value = _state.value.copy(busy = true, message = null)
        job = scope.launch {
            val request = RequestMargins(margins.left, margins.top, margins.right, margins.bottom)
            when (val result = provider.outpaint(source, request)) {
                is Result.Success -> store(document, margins, result.value, onCommitted)
                is Result.Failure -> _state.value =
                    _state.value.copy(busy = false, message = messageFor(result.error))
            }
        }
    }

    /**
     * The result file is written first: a document pointing at a file that is not there is worse
     * than an expansion the user has to repeat. `withOutpaint` is what enforces §3's rules — index
     * 0, at most one, the mask-op guard and the crop re-normalization — so this only stores.
     */
    private suspend fun store(
        document: EditDocument,
        margins: Margins,
        result: Bitmap,
        onCommitted: (EditDocument) -> Unit,
    ) {
        val outpaintId = newId()
        _state.value = when (val saved = saveResult(outpaintId, result)) {
            is Result.Success -> {
                onCommitted(document.withOutpaint(margins, saved.value, outpaintId))
                _state.value.copy(busy = false, margins = Margins.None)
            }
            is Result.Failure -> _state.value.copy(busy = false, message = R.string.expand_failed)
        }
    }

    /** DESIGN.md §7: cancelling leaves the document byte-for-byte untouched. */
    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    /** 취소 or system back: the margins die with the sheet, and nothing was committed. */
    fun close() {
        job?.cancel()
        _state.value = _state.value.copy(margins = Margins.None, busy = false)
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * §6's last two rows. A safety block is an `Invalid` with a recognizable prefix (duplicated
     * for the reason `EraseController` gives: the client is `internal` to `core:ai`). The
     * still-white guard's `Unavailable` and the aspect guard's `Unsupported` say the same thing
     * to the user — the border did not get painted — so they share one line.
     */
    @StringRes
    private fun messageFor(error: AppError): Int =
        if (error is AppError.Invalid && error.detail.startsWith(BLOCKED_PREFIX)) {
            R.string.expand_blocked
        } else {
            R.string.expand_failed
        }

    private companion object {
        const val BLOCKED_PREFIX = "blocked:"
    }
}
