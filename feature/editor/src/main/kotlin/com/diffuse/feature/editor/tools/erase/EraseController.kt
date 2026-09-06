package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EraseProvider
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
 * specs/generative_erase.md §9. What tapping the tool should do. Returned rather than acted on,
 * because opening the 서버 설정 sheet is the selection tool's to do — there is only one sheet.
 */
enum class EraseTap {
    Run,

    /** The key is missing, which the settings sheet can fix; a snackbar alone cannot. */
    OpenSettings,

    /** The reason is already in [EraseState.message]; nothing more to offer. */
    Refused,
}

/** specs/generative_erase.md §5. The tool has no sheet: tapping it runs the model. */
data class EraseState(
    val availability: Availability = Availability.Unavailable(AppError.Unavailable),
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {
    val enabled: Boolean get() = availability is Availability.Ready
}

/**
 * Runs the generative eraser and hands the result back. Committing it to the document is
 * `EditorViewModel`'s job, because only it owns the history stack — the same split the
 * selection tool uses.
 */
class EraseController(
    private val provider: EraseProvider,
    private val commit: EraseCommit,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(EraseState())
    val state: StateFlow<EraseState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            provider.availability.collect { _state.value = _state.value.copy(availability = it) }
        }
    }

    /**
     * specs/generative_erase.md §9. A greyed tool still explains itself, and the four states it
     * can be greyed in are not interchangeable: "select something first" and "paste a key" ask
     * the user for completely different things.
     *
     * The order is §9's table order — a missing selection is reported before a missing key.
     */
    fun onToolTapped(hasSelection: Boolean): EraseTap {
        val availability = _state.value.availability
        return when {
            !hasSelection -> refuse(R.string.erase_needs_selection)
            availability is Availability.Ready -> EraseTap.Run
            // §7: for the Gemini provider `Unavailable` carries `Invalid` only when the key is
            // blank, so this is the "no key" row rather than a general outage.
            (availability as? Availability.Unavailable)?.reason is AppError.Invalid -> {
                showMessage(R.string.erase_needs_key)
                EraseTap.OpenSettings
            }
            else -> refuse(R.string.erase_failed)
        }
    }

    private fun refuse(res: Int): EraseTap {
        showMessage(res)
        return EraseTap.Refused
    }

    /**
     * specs/generative_erase.md §5, §6. Runs the model and, only once the result is safely on
     * disk, hands the new document to [onCommitted] as one history entry.
     *
     * The whole sequence lives here rather than in `EditorViewModel` so the tool is one object,
     * the way the selection tool is. Writing files and pushing history are the two things it
     * cannot do itself: the repository is the ViewModel's, and so is the history stack.
     *
     * A null [image], [mask] or [document] — or a document with nothing selected — means there is
     * nothing to erase, which is a message rather than a no-op: the user pressed a button and
     * deserves to know why nothing happened.
     *
     * T50: the model is shown the selection **plus a margin**, and that dilated mask is what the
     * document stores, because the renderer composes the result through it.
     */
    fun runAndCommit(
        image: Bitmap?,
        mask: Bitmap?,
        document: EditDocument?,
        onCommitted: (EditDocument) -> Unit,
    ) {
        if (image == null || mask == null || document?.activeMaskId == null) {
            showMessage(R.string.erase_needs_selection)
            return
        }
        job?.cancel()
        _state.value = _state.value.copy(busy = true, message = null)
        job = scope.launch {
            val dilated = EraseMask.dilated(mask)
            when (val result = provider.erase(image, dilated, hint = null)) {
                is Result.Success -> store(document, dilated, result.value, onCommitted)
                is Result.Failure -> _state.value =
                    _state.value.copy(busy = false, message = messageFor(result.error))
            }
        }
    }

    private suspend fun store(
        document: EditDocument,
        dilated: Bitmap,
        result: Bitmap,
        onCommitted: (EditDocument) -> Unit,
    ) {
        _state.value = when (val committed = commit.apply(document, dilated, result)) {
            is Result.Success -> {
                onCommitted(committed.value)
                _state.value.copy(busy = false)
            }
            is Result.Failure -> _state.value.copy(busy = false, message = R.string.erase_failed)
        }
    }

    /**
     * specs/generative_erase.md §9: a safety block is an `Invalid` with a recognizable prefix,
     * not its own `AppError`, so this is where the two are told apart.
     *
     * The prefix is duplicated from `GeminiEraseClient` rather than imported: that class is
     * `internal` to `core:ai`, which is the boundary working as intended — the feature knows the
     * contract, not the client.
     */
    private fun messageFor(error: AppError): Int =
        if (error is AppError.Invalid && error.detail.startsWith(BLOCKED_PREFIX)) {
            R.string.erase_blocked
        } else {
            R.string.erase_failed
        }

    /** DESIGN.md §7: cancelling leaves the document byte-for-byte untouched. */
    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val BLOCKED_PREFIX = "blocked:"
    }
}
