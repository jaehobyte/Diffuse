package com.diffuse.feature.editor.tools.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asImageBitmap
import com.diffuse.core.ai.topaz.TopazConfig
import com.diffuse.core.ai.topaz.TopazEstimate
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazOutcome
import com.diffuse.core.ai.topaz.TopazProvider
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ai.topaz.TopazStage
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.TopazSessionFiles
import com.diffuse.core.data.TopazWorkspace
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the Topaz tool cannot know by itself: how to render the open document, and where a result
 * goes once the user picks it. `feature:editor` never sees `feature:export` or the nav graph
 * (architecture.md §4.1), so both arrive through here.
 */
interface TopazHost {
    /** specs/topaz_enhancement.md §5: the committed document through `Renderer.full`. */
    suspend fun renderInput(document: EditDocument): Bitmap?

    /** The same document at preview size, for the compare's "전". */
    suspend fun renderBefore(document: EditDocument): Bitmap?

    /** §5: the file, byte for byte, into the gallery; never re-encoded. */
    suspend fun saveToGallery(file: File, mimeType: String, width: Int, height: Int): Result<Unit>

    /** §5: a new project whose source is [file], at the working size. Returns its id. */
    suspend fun createProject(file: File): Result<String>

    /** A project created for a navigation that is no longer wanted. */
    suspend fun discardProject(projectId: String)

    /** The new edit copy is open; this editor's sheet closes. */
    fun onOpenedCopy()
}

/**
 * specs/topaz_enhancement.md §5, vibe_edit.md §19: the one way a document becomes a Topaz result —
 * rendered in full, written as the upload, submitted once through [provider], validated and decoded
 * at preview size. The 화질 개선 sheet ([TopazController]) and a plan's workflow (`DirectController`)
 * both run it, so there is one client and one set of checks. It holds no state: run identity, file
 * ownership and what a late answer may touch are each caller's.
 */
class TopazProcessor(
    private val provider: TopazProvider,
    private val workspace: TopazWorkspace,
    /** Rendering, the gallery copy and a new project: what this module cannot reach by itself. */
    val host: TopazHost,
    private val dispatchers: DispatcherProvider,
) {

    val config: StateFlow<TopazConfig> get() = provider.config

    suspend fun open(): Result<TopazSessionFiles> = workspace.open()

    /** §4: the free price check; it creates no job. */
    suspend fun estimate(job: TopazJob): TopazOutcome<TopazEstimate> = provider.estimate(job)

    /**
     * §5: [document] through `Renderer.full` into [files]' upload, and at preview size for the "전"
     * and the result's decode. Local; no network. Null when either render or the write failed.
     */
    suspend fun prepare(document: EditDocument, files: TopazSessionFiles): TopazInput? {
        val rendered = host.renderInput(document)
        val before = host.renderBefore(document)
        if (rendered == null || before == null) return null
        return withContext(dispatchers.io) {
            val written = files.input.outputStream().use {
                rendered.compress(Bitmap.CompressFormat.JPEG, INPUT_JPEG_QUALITY, it)
            }
            TopazInput(rendered.width, rendered.height, document.hasAlpha, before.asImageBitmap()).takeIf { written }
        }
    }

    /**
     * Submits [job] once on [files]' upload into the run's own output file, then decodes the result at
     * [input]'s preview size (sampled, never whole). A result that does not decode is deleted and is
     * [TopazFailure.OutputInvalid]. The caller decides whether a result that arrives late is wanted.
     */
    // A job, where it reads and writes, what it was asked with, and where progress goes; a failure,
    // an undecodable file and a result are three answers.
    @Suppress("LongParameterList", "ReturnCount")
    suspend fun process(
        job: TopazJob,
        files: TopazSessionFiles,
        run: Long,
        input: TopazInput,
        options: TopazOptions,
        onStage: (TopazStage) -> Unit,
    ): TopazOutcome<TopazResult> {
        val output = files.output(run)
        val outcome = provider.enhance(job, files.input, output, onStage)
        if (outcome is TopazOutcome.Failure) return outcome
        val done = (outcome as TopazOutcome.Success).value
        val after = try {
            withContext(dispatchers.io) { decodePreview(done.file, input.before.width, input.before.height) }
        } catch (e: CancellationException) {
            // A provider that ignored its caller's cancellation still answered: its file goes.
            delete(done.file)
            throw e
        }?.asImageBitmap()
        if (after == null) {
            delete(done.file)
            return TopazOutcome.Failure(TopazFailure.OutputInvalid)
        }
        return TopazOutcome.Success(
            TopazResult(
                file = done.file,
                width = done.width,
                height = done.height,
                mimeType = done.mimeType,
                purpose = job.purpose,
                options = options,
                after = after,
                credits = done.credits,
            ),
        )
    }

    /** Deletes one file of a session even from a cancelled caller — a late result nobody wants. */
    suspend fun delete(file: File) {
        withContext(NonCancellable) { workspace.delete(file) }
    }

    suspend fun discard(files: TopazSessionFiles) = workspace.discard(files)

    private companion object {
        const val INPUT_JPEG_QUALITY = 95
    }
}

/**
 * specs/topaz_enhancement.md. The shared Topaz tool: one controller for 화질 개선 and every
 * shortcut into it. Opening, choosing and comparing are free; only [run] submits a paid job, and
 * it submits exactly one per tap.
 *
 * A run is identified by [runToken] and the document [snapshot] it was rendered from. Anything a
 * run reports after its token moved on — a cancel, a close, a document change, a key change —
 * touches nothing.
 */
// One public function per thing the sheet can ask for.
@Suppress("TooManyFunctions")
class TopazController(
    private val processor: TopazProcessor,
    private val scope: CoroutineScope,
) {

    private val host: TopazHost get() = processor.host

    private val _state = MutableStateFlow(TopazState(configured = processor.config.value.isConfigured))
    val state: StateFlow<TopazState> = _state.asStateFlow()

    private var session: TopazSessionFiles? = null
    private var snapshot: EditDocument? = null
    private var runToken = 0L
    private var work: Job? = null

    /** A save or a create that is reading the result file; [close] waits for it. */
    private var reader: Job? = null
    private var generation = processor.config.value.generation

    init {
        scope.launch {
            processor.config.collect { config ->
                val changed = config.generation != generation
                generation = config.generation
                _state.value = _state.value.copy(configured = config.isConfigured)
                // §4: a key change voids whatever is in flight under the old one.
                if (changed && _state.value.phase.isRun()) abandonRun(R.string.topaz_key_changed)
            }
        }
    }

    /**
     * Opens the sheet on [group]; prepares the input locally and sends nothing. [options] are a
     * plan's (specs/vibe_edit.md §16): used only when they are ones [purpose] offers — the check
     * `TopazJob.problem()` makes — and otherwise the purpose's defaults.
     */
    fun open(group: TopazGroup, purpose: TopazPurpose?, document: EditDocument?, options: TopazOptions? = null) {
        val chosen = purpose?.takeIf { it in group.purposes } ?: group.purposes.first()
        val offered = options?.takeIf { TopazJob(chosen, it, 1, 1).problem() == null }
        _state.value = TopazState(
            group = group,
            purpose = chosen,
            options = offered ?: TopazOptions.defaultsFor(chosen),
            configured = processor.config.value.isConfigured,
        )
        document?.let(::prepare)
    }

    fun selectPurpose(purpose: TopazPurpose) {
        val current = _state.value
        if (purpose == current.purpose || current.group?.purposes?.contains(purpose) != true) return
        _state.value = current.copy(
            purpose = purpose,
            options = TopazOptions.defaultsFor(purpose),
            estimate = TopazEstimateState.NotChecked,
        )
    }

    fun setScale(scale: Int) = updateOptions { it.copy(scale = scale) }

    fun setFaceRecovery(on: Boolean) = updateOptions { it.copy(faceRecovery = on) }

    /** null is Autopilot (§3). */
    fun setSharpenStrength(strength: Float?) = updateOptions { it.copy(sharpenStrength = strength) }

    /** §4: the free price check, only when asked for. */
    fun checkEstimate() {
        val job = _state.value.job ?: return
        if (_state.value.estimate == TopazEstimateState.Checking) return
        _state.value = _state.value.copy(estimate = TopazEstimateState.Checking)
        val asked = _state.value.purpose to _state.value.options
        scope.launch {
            val outcome = processor.estimate(job)
            val current = _state.value
            if (current.purpose to current.options != asked) return@launch
            _state.value = current.copy(
                estimate = when (outcome) {
                    is TopazOutcome.Success -> TopazEstimateState.Known(outcome.value.credits)
                    is TopazOutcome.Failure -> TopazEstimateState.Unknown
                },
            )
        }
    }

    /** §2: 실행. One job per tap; a second tap while one runs does nothing. */
    fun run() {
        val current = _state.value
        val job = current.job
        val files = session
        if (job == null || files == null || !current.canRun) return
        val input = current.input ?: return
        val token = ++runToken
        _state.value = current.copy(phase = TopazPhase.Uploading, progress = null, failure = null, comparing = false)
        work = scope.launch {
            val outcome = processor.process(job, files, token, input, current.options) { stage ->
                if (token == runToken) onStage(stage)
            }
            if (token != runToken) {
                (outcome as? TopazOutcome.Success)?.let { processor.delete(it.value.file) }
                return@launch
            }
            when (outcome) {
                is TopazOutcome.Success -> showResult(outcome.value)
                is TopazOutcome.Failure -> fail(outcome.failure)
            }
        }
    }

    /** DESIGN.md §7: the overlay's cancel. The provider asks Topaz to cancel, best effort. */
    fun cancelWork() {
        when (_state.value.phase) {
            TopazPhase.Idle -> Unit
            TopazPhase.Saving -> {
                reader?.cancel()
                _state.value = _state.value.copy(phase = TopazPhase.Idle)
            }
            // A create already under way finishes and is discarded; see [openCopy].
            TopazPhase.Opening -> _state.value = _state.value.copy(phase = TopazPhase.Idle)
            TopazPhase.Preparing -> {
                runToken++
                work?.cancel()
                _state.value = _state.value.copy(phase = TopazPhase.Idle)
            }
            else -> abandonRun(R.string.topaz_cancelled)
        }
    }

    fun setComparing(comparing: Boolean) {
        if (_state.value.result == null) return
        _state.value = _state.value.copy(comparing = comparing)
    }

    /** §5: 고해상도 저장. A retry copies the same file again; it never calls Topaz. */
    fun save() {
        val result = _state.value.result ?: return
        if (_state.value.phase != TopazPhase.Idle) return
        _state.value = _state.value.copy(phase = TopazPhase.Saving)
        reader = scope.launch {
            val saved = host.saveToGallery(result.file, result.mimeType, result.width, result.height)
            _state.value = when (saved) {
                is Result.Success -> _state.value.copy(
                    phase = TopazPhase.Idle,
                    saved = true,
                    message = R.string.topaz_saved,
                )
                is Result.Failure -> _state.value.copy(phase = TopazPhase.Idle, message = R.string.topaz_save_failed)
            }
        }
    }

    /**
     * §5: 새 편집본으로 열기. The project is created once; once creation has started it runs to
     * the end, and a create nobody is waiting for any more is deleted rather than left in Browse.
     */
    fun openCopy() {
        val result = _state.value.result ?: return
        // Created and waiting for the route: a second tap must not make a second project.
        if (_state.value.phase != TopazPhase.Idle || _state.value.openedProjectId != null) return
        _state.value = _state.value.copy(phase = TopazPhase.Opening)
        val token = runToken
        reader = scope.launch {
            withContext(NonCancellable) {
                val created = host.createProject(result.file)
                // Still wanted only if nobody cancelled, closed or re-ran in the meantime.
                val wanted = token == runToken && _state.value.phase == TopazPhase.Opening
                when {
                    created is Result.Success && wanted ->
                        _state.value = _state.value.copy(phase = TopazPhase.Idle, openedProjectId = created.value)
                    created is Result.Success -> host.discardProject(created.value)
                    wanted -> _state.value =
                        _state.value.copy(phase = TopazPhase.Idle, message = R.string.topaz_open_failed)
                }
            }
        }
    }

    /** The route navigated; the sheet in this editor is done. */
    fun onNavigated() {
        if (_state.value.openedProjectId == null) return
        _state.value = _state.value.copy(openedProjectId = null)
        host.onOpenedCopy()
    }

    /**
     * §2, §5: the document under an open sheet changed (undo, redo). The input and any result
     * describe a picture that is gone, so both are rebuilt; a run in flight is abandoned.
     */
    fun onDocumentChanged(document: EditDocument?) {
        if (!_state.value.isOpen || document == null || document == snapshot) return
        if (_state.value.phase.isRun()) abandonRun(R.string.topaz_document_changed)
        if (_state.value.phase == TopazPhase.Idle || _state.value.phase == TopazPhase.Preparing) {
            dropResult()
            prepare(document)
        }
    }

    /**
     * 취소, 닫기 or system back. A run is abandoned; this session's files go once nothing is
     * reading them — a save in progress is cancelled first, a create is waited for.
     */
    fun close() {
        if (!_state.value.isOpen) return
        if (_state.value.phase.isRun()) abandonRun(null)
        runToken++
        work?.cancel()
        val files = session
        val pending = reader
        session = null
        snapshot = null
        _state.value = TopazState(configured = _state.value.configured, message = _state.value.message)
        if (files != null) {
            scope.launch {
                // A save stops and removes its pending row; a create cannot be cancelled and is
                // waited for (it discards itself, see [openCopy]).
                pending?.cancelAndJoin()
                processor.discard(files)
            }
        }
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- internals --------------------------------------------------------

    /** §3: options change locally; a stale estimate is dropped, never re-fetched by itself. */
    private fun updateOptions(change: (TopazOptions) -> TopazOptions) {
        val current = _state.value
        val next = change(current.options)
        if (next == current.options) return
        _state.value = current.copy(options = next, estimate = TopazEstimateState.NotChecked)
    }

    /** §5: renders, encodes the upload and the "전" frame. Local; no network. */
    private fun prepare(document: EditDocument) {
        val token = ++runToken
        snapshot = document
        _state.value = _state.value.copy(
            phase = TopazPhase.Preparing,
            input = null,
            estimate = TopazEstimateState.NotChecked,
        )
        work?.cancel()
        work = scope.launch {
            // Not cancellable once asked: a folder made for a run that moved on is discarded here,
            // not lost with its path.
            val files = session ?: when (val opened = withContext(NonCancellable) { processor.open() }) {
                is Result.Success -> opened.value
                is Result.Failure -> return@launch prepared(token, null)
            }
            if (token != runToken) {
                if (files !== session) withContext(NonCancellable) { processor.discard(files) }
                return@launch
            }
            session = files
            prepared(token, processor.prepare(document, files))
        }
    }

    private fun prepared(token: Long, input: TopazInput?) {
        if (token != runToken) return
        _state.value = _state.value.copy(
            phase = TopazPhase.Idle,
            input = input,
            message = if (input == null) R.string.topaz_prepare_failed else _state.value.message,
        )
    }

    private fun onStage(stage: TopazStage) {
        val (phase, progress) = stage.phase()
        _state.value = _state.value.copy(phase = phase, progress = progress)
    }

    private suspend fun showResult(result: TopazResult) {
        _state.value.result?.let { previous -> processor.delete(previous.file) }
        _state.value = _state.value.copy(phase = TopazPhase.Idle, progress = null, saved = false, result = result)
    }

    private fun fail(failure: TopazFailure) {
        _state.value = _state.value.copy(
            phase = TopazPhase.Idle,
            progress = null,
            failure = failure,
            message = messageFor(failure),
        )
    }

    /** Stops listening to the run; the provider's cancellation asks Topaz to stop it. */
    private fun abandonRun(@StringRes message: Int?) {
        runToken++
        work?.cancel()
        _state.value = _state.value.copy(
            phase = TopazPhase.Idle,
            progress = null,
            message = message ?: _state.value.message,
        )
    }

    private fun dropResult() {
        _state.value.result?.let { scope.launch { processor.delete(it.file) } }
        _state.value = _state.value.copy(result = null, comparing = false, saved = false, failure = null)
    }

    private fun TopazPhase.isRun(): Boolean = this in RUN_PHASES

    private companion object {
        val RUN_PHASES =
            setOf(TopazPhase.Uploading, TopazPhase.Queued, TopazPhase.Processing, TopazPhase.Downloading)
    }
}

/**
 * §5: the result at the "전" frame's size, decoded with `inSampleSize` so the full-resolution
 * file never becomes one bitmap. Same aspect, so the canvas keeps its zoom and pan across compare.
 */
internal fun decodePreview(file: File, width: Int, height: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    return when {
        decoded == null || decoded.width == width && decoded.height == height -> decoded
        else -> Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() }
    }
}
