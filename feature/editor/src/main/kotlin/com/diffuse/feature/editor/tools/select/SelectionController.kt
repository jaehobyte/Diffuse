package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.topaz.TopazSettings
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * specs/selection_tool.md, without the sheet: availability, the session, and the one point run
 * a tap on the photo refines. It is deliberately not part of `EditorViewModel` — the session
 * outlives any tool and has its own lifecycle.
 *
 * Nothing here touches the document. Every mask a run produces is handed to [onSegmented] the
 * moment it lands, and the owner commits it — only it owns the history stack.
 */
// The 서버 설정 sheet's save is one function per server it carries.
@Suppress("TooManyFunctions", "LongParameterList")
class SelectionController(
    private val segmentation: SegmentationProvider,
    private val settings: Sam3Settings,
    /**
     * specs/generative_erase.md §8: the 서버 설정 sheet is one sheet for two providers, and
     * this controller already owns its lifecycle. The 지우기 tool opens the same one.
     */
    private val geminiSettings: GeminiSettings,
    /** specs/topaz_enhancement.md §4: the same sheet carries the Topaz key, stored apart. */
    private val topazSettings: TopazSettings,
    private val scope: CoroutineScope,
    private val onSegmented: (Bitmap) -> Unit = {},
) {

    private val _state = MutableStateFlow(SelectionState())
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    /** In flight `byPoints`; a new prompt supersedes it rather than queueing (§4). */
    private var job: Job? = null

    /**
     * Bumped by every [open] and by giving up on one; an `open` that comes back under an older
     * ticket is not wanted any more. See [open].
     */
    private var openTicket = 0

    /** Whether this backend has ever answered. See [explain]. */
    private var everReady = false

    init {
        scope.launch {
            segmentation.availability.collect { availability ->
                if (availability is Availability.Ready) everReady = true
                _state.value = _state.value.copy(availability = availability)
            }
        }
        scope.launch {
            settings.config.collect { config ->
                _state.value = _state.value.copy(config = config)
                segmentation.refresh()
            }
        }
        scope.launch {
            geminiSettings.config.collect { config ->
                _state.value = _state.value.copy(geminiApiKey = config.apiKey)
            }
        }
        scope.launch {
            topazSettings.config.collect { config ->
                _state.value = _state.value.copy(topazApiKey = config.apiKey)
            }
        }
    }

    /**
     * specs/selection_tool.md §1: a tap that cannot be served still explains itself. When the
     * reason is that nothing is configured, the settings sheet is more use than a snackbar.
     *
     * @return true when the tap may go ahead.
     */
    fun onPhotoTapped(): Boolean {
        val availability = _state.value.availability
        if (availability is Availability.Ready) return true
        explain((availability as? Availability.Unavailable)?.reason)
        return false
    }

    /**
     * Only one question matters: can the user fix this by editing the settings?
     *
     * - `Invalid` (no address) and `Unauthorized` (the token was rejected) always can be —
     *   `/healthz` needs no auth, so a bad token sits behind a perfectly healthy server.
     * - Anything else, from a backend that has never once answered, is most likely a wrong
     *   address. A default that does not resolve — the emulator's `10.0.2.2` on a real phone —
     *   would otherwise grey the tool forever with no way in.
     * - Anything else, from a backend that has answered before, is a rate limit, a dropped
     *   connection or a restart. Say so and re-probe; do not throw a sheet at someone whose
     *   settings are fine.
     */
    private fun explain(reason: AppError?) {
        val fixableInSettings =
            reason is AppError.Invalid || reason is AppError.Unauthorized || !everReady
        if (fixableInSettings) {
            setSettingsVisible(true)
            return
        }
        _state.value = _state.value.copy(message = R.string.select_unavailable_offline)
        scope.launch { segmentation.refresh() }
    }

    /**
     * specs/selection_tool.md §9: the session is opened on the **current preview**, not the
     * source, so the mask lines up with what the user is looking at. Once open it is reused for
     * the rest of the editor session.
     *
     * Nothing opens one early: the first upload happens on the first tap, or when 지시 needs
     * it, and never just because the AI level was opened.
     */
    fun open(preview: Bitmap) {
        val current = _state.value
        if (current.session != null) return
        // `preparing` as well as `session`: an upload takes seconds, and without this a second
        // tap in that window starts a second one.
        if (current.preparing) return
        val ticket = ++openTicket
        _state.value = current.copy(preparing = true)
        // Deliberately not held in `job`. Cancelling an upload that is already on the wire does
        // not un-create the session the server makes from it — it only loses the id, leaving an
        // orphan to occupy one of the backend's four slots until its TTL expires. So the call
        // always runs to completion, and a session nobody wants any more is released below.
        // `preparing` was already reset when it was given up on, and may belong to a newer open.
        scope.launch {
            val opened = segmentation.open(preview)
            val wanted = ticket == openTicket
            when {
                opened is Result.Success && !wanted -> segmentation.close(opened.value)
                !wanted -> Unit
                opened is Result.Success -> {
                    _state.value = _state.value.copy(session = opened.value, preparing = false)
                    replayPending()
                }
                else -> _state.value = _state.value.copy(
                    preparing = false,
                    pending = null,
                    message = R.string.select_failed,
                )
            }
        }
    }

    /** The tap taken while the upload was on the wire is the next point of the run. */
    private fun replayPending() {
        val pending = _state.value.pending ?: return
        _state.value = _state.value.copy(pending = null)
        addPoint(pending.x, pending.y, pending.foreground)
    }

    /**
     * specs/selection_tool.md §6: the session outlives any tool, but not the editor. Leaving
     * without this leaks it for the backend's whole TTL.
     */
    suspend fun release() {
        openTicket++
        job?.cancel()
        _state.value.session?.let { segmentation.close(it) }
        _state.value = _state.value.cleared()
            .copy(session = null, preparing = false, pending = null, busy = false)
    }

    /**
     * specs/selection_tool.md §2: tap adds a foreground point, long-press a background one.
     * Tapped before the upload has come back, the point is kept and segmented the moment the
     * session is ready — so the first tap on the photo is also what opens the session.
     */
    fun addPoint(x: Float, y: Float, foreground: Boolean) {
        val current = _state.value
        if (current.session == null) {
            if (current.preparing) _state.value = current.copy(pending = PendingPoint(x, y, foreground))
            return
        }
        segment(current.withPoint(PointF(x, y), foreground))
    }

    /**
     * The run ends and the session stays. The owner calls this when the document's active mask
     * stops being the one this run made — undo, another project — so the next tap starts a new
     * selection rather than refining points for a mask that is gone.
     */
    fun clear() {
        job?.cancel()
        _state.value = _state.value.cleared().copy(busy = false)
    }

    /** DESIGN.md §7: AI work always offers a way out. */
    fun cancelWork() {
        // A prompt creates nothing, so cancelling one is free; an `open` is not, so it is
        // marked abandoned instead and released as soon as its id is known.
        openTicket++
        job?.cancel()
        // Whatever was accumulated stays exactly as it was.
        _state.value = _state.value.copy(preparing = false, pending = null, busy = false)
    }

    fun saveSettings(baseUrl: String, token: String, geminiApiKey: String, topazApiKey: String) {
        settings.update(baseUrl, token)
        geminiSettings.update(geminiApiKey)
        // An unchanged key is not a change: TopazSettings keeps its generation, so a run in
        // flight survives a save that only touched another server.
        topazSettings.update(topazApiKey)
        _state.value = _state.value.copy(showSettings = false)
    }

    /**
     * specs/generative_erase.md §9: the 지우기 tool opens the same 서버 설정 sheet when its key is
     * missing. One sheet, one owner — this controller — so one function opens and closes it.
     */
    fun setSettingsVisible(visible: Boolean) {
        _state.value = _state.value.copy(showSettings = visible)
    }

    fun showMessage(res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Only the latest prompt matters: an in-flight call is cancelled rather than queued, and the
     * previous mask stays on screen until the new one lands (specs/selection_tool.md §5). A
     * result that lands is the run's whole selection, and goes straight to [onSegmented].
     */
    private fun segment(next: SelectionState) {
        val session = next.session ?: return
        val prompt = next.prompt ?: return
        job?.cancel()
        _state.value = next.copy(busy = true)
        job = scope.launch {
            when (val result = segmentation.byPoints(session, prompt)) {
                is Result.Success -> {
                    val mask = result.value.alpha.takeUnless(MaskOutline::isEmpty)
                    _state.value = _state.value.copy(mask = mask, busy = false)
                    // An empty answer — the last point excluded everything — commits nothing;
                    // the document keeps the mask it had.
                    if (mask != null) onSegmented(mask)
                }
                // The mask the user already had survives a failed prompt.
                is Result.Failure -> _state.value = _state.value.copy(busy = false, message = R.string.select_failed)
            }
        }
    }
}
