package com.diffuse.feature.editor.tools.fill

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FillProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * specs/generative_fill.md §6. What tapping the tool should do, returned rather than acted on:
 * the 서버 설정 sheet is `SelectionController`'s, and there is one of it (generative_erase.md §9).
 */
enum class FillTap {
    /** Open the sheet, which is where the noun comes from. */
    Open,

    /** The key is blank, which only the settings sheet can fix. */
    OpenSettings,

    /** The reason is already in [FillState.message]; nothing more to offer. */
    Refused,
}

/** specs/generative_fill.md §6. Sheet state; nothing here reaches the document until 적용. */
data class FillState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val prompt: String = "",
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val enabled: Boolean get() = availability is Availability.Ready

    /** §6: 적용 is disabled while the prompt is blank, through `EditSheet.applyEnabled`. */
    val canApply: Boolean get() = prompt.isNotBlank() && !busy
}

/**
 * Runs the generative fill and hands the result back. Committing it to the document is
 * `EditorViewModel`'s job, because only it owns the history stack — the same split 지우기 uses.
 *
 * Unlike 지우기 the mask is **not** dilated: a margin is what keeps a removed object from leaving
 * a halo, and here it would make the thing the user asked for larger than the region they chose.
 */
class FillController(
    private val provider: FillProvider,
    private val saveResult: suspend (fillId: String, result: Bitmap) -> Result<ImageRef>,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(FillState())
    val state: StateFlow<FillState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
        }
    }

    /**
     * specs/generative_fill.md §6, in that table's order: a missing selection is reported before
     * a missing key, because the two ask the user for completely different things.
     */
    fun onToolTapped(hasSelection: Boolean): FillTap {
        val availability = _state.value.availability
        return when {
            !hasSelection -> {
                showMessage(R.string.fill_needs_selection)
                FillTap.Refused
            }
            availability is Availability.Ready -> FillTap.Open
            // §4: availability is derived from the key with no probe, so `Invalid` here is the
            // "no key" row rather than a general outage.
            (availability as? Availability.Unavailable)?.reason is AppError.Invalid -> {
                showMessage(R.string.fill_needs_key)
                FillTap.OpenSettings
            }
            else -> {
                showMessage(R.string.fill_failed)
                FillTap.Refused
            }
        }
    }

    fun setPrompt(prompt: String) {
        _state.value = _state.value.copy(prompt = prompt)
    }

    /**
     * specs/generative_fill.md §6. Runs the model and, only once the result is on disk, hands the
     * new document to [onCommitted] as one history entry. On failure the prompt survives, so a
     * retry costs no retyping.
     */
    fun runAndCommit(
        image: Bitmap?,
        mask: Bitmap?,
        document: EditDocument?,
        onCommitted: (EditDocument) -> Unit,
    ) {
        val prompt = _state.value.prompt
        val maskId = document?.activeMaskId
        if (image == null || mask == null || maskId == null) {
            showMessage(R.string.fill_needs_selection)
            return
        }
        if (prompt.isBlank()) return
        job?.cancel()
        _state.value = _state.value.copy(busy = true, message = null)
        job = scope.launch {
            when (val result = provider.fill(image, mask, prompt)) {
                is Result.Success -> store(document, maskId, prompt, result.value, onCommitted)
                is Result.Failure -> _state.value =
                    _state.value.copy(busy = false, message = messageFor(result.error))
            }
        }
    }

    /**
     * The result file is written first: a document pointing at a file that is not there is worse
     * than a fill the user has to repeat. The op names the user's own selection, which stays
     * active — nothing about a fill replaces what was chosen.
     */
    private suspend fun store(
        document: EditDocument,
        maskId: String,
        prompt: String,
        result: Bitmap,
        onCommitted: (EditDocument) -> Unit,
    ) {
        val fillId = newId()
        _state.value = when (val saved = saveResult(fillId, result)) {
            is Result.Success -> {
                onCommitted(document.withGenerativeFill(maskId, saved.value, prompt, fillId))
                _state.value.copy(busy = false, prompt = "")
            }
            is Result.Failure -> _state.value.copy(busy = false, message = R.string.fill_failed)
        }
    }

    /** DESIGN.md §7: cancelling leaves the document byte-for-byte untouched. */
    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    /** 취소 or system back: the prompt dies with the sheet, and nothing was committed. */
    fun close() {
        job?.cancel()
        _state.value = _state.value.copy(prompt = "", busy = false)
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * specs/generative_fill.md §6's last three rows. A safety block is an `Invalid` with a
     * recognizable prefix (duplicated for the reason `EraseController` gives: the client is
     * `internal` to `core:ai`). `Unavailable` is the still-white guard's answer — the one
     * failure this tool can tell the user something useful about.
     */
    @StringRes
    private fun messageFor(error: AppError): Int = when {
        error is AppError.Invalid && error.detail.startsWith(BLOCKED_PREFIX) -> R.string.fill_blocked
        error == AppError.Unavailable -> R.string.fill_empty
        else -> R.string.fill_failed
    }

    private companion object {
        const val BLOCKED_PREFIX = "blocked:"
    }
}
