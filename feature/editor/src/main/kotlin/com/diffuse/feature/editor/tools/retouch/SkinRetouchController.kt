package com.diffuse.feature.editor.tools.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asImageBitmap
import com.diffuse.core.ai.CorrectionOutcome
import com.diffuse.core.ai.DetectedFace
import com.diffuse.core.ai.FaceDetail
import com.diffuse.core.ai.FaceRegionAnalyzer
import com.diffuse.core.ai.PreparedCorrection
import com.diffuse.core.ai.SkinAllowedMask
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.SkinRetouchProvider
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.RetouchKind
import com.diffuse.core.imaging.model.SkinRetouchSettings
import com.diffuse.core.imaging.model.skinRetouchBase
import com.diffuse.core.imaging.model.skinRetouchInsertIndex
import com.diffuse.core.imaging.render.SkinRetouchComposite
import com.diffuse.core.imaging.render.SkinRetouchLayer
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the controller needs from the editor: the renderer, the project's files and its history. */
interface SkinRetouchHost {

    /** The canonical working-size render of [document] (pipeline §4 step 2), or null. */
    suspend fun renderBase(document: EditDocument): Bitmap?

    suspend fun save(retouchId: String, maskId: String, result: Bitmap, support: Bitmap): Result<SkinRetouchFiles>

    /** Deletes this request's files; never files the saved document references. */
    suspend fun discard(retouchId: String, maskId: String)

    fun putTransient(ref: ImageRef, bitmap: Bitmap)

    fun removeTransient(ref: ImageRef)

    /** The document on screen now, for the checks before a late answer may write anything. */
    fun currentDocument(): EditDocument?

    /** The draft changed; the canvas should render [SkinRetouchController.draftDocument] again. */
    fun onDraftChanged()

    /** One history entry, then the sheet closes. */
    fun commit(document: EditDocument)
}

/**
 * specs/skin_retouch.md §3–§5, pipeline §4–§7. One face, four independent strengths, one commit.
 *
 * The session is keyed on the entry document and a generation; every request carries the face and
 * request generations it was made for. A late answer — from a provider that ignored cancellation,
 * or after a face, settings or document change — is checked when it arrives and again right before
 * the commit, and is dropped rather than written (§7). An old `finally` never clears a newer
 * request's busy flag, because only the job that owns the flag may clear it.
 *
 * Pixels: the analysis and every candidate come from one immutable base ROI cut from the prefix
 * render (§4). Strength changes composite locally on the ROI (§5) and hand the renderer a
 * preview-scale transient result, so a slider never uploads, never infers and never writes a file.
 */
// The editor seams arrive as constructor parameters, the way DirectController's do.
@Suppress("TooManyFunctions", "LongParameterList")
class SkinRetouchController(
    private val provider: SkinRetouchProvider,
    private val analyzer: FaceRegionAnalyzer,
    serverSaves: StateFlow<Int>,
    serverHost: Flow<String>,
    private val host: SkinRetouchHost,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val previewLongEdgePx: Int,
) {

    private val _state = MutableStateFlow(SkinRetouchState())
    val state: StateFlow<SkinRetouchState> = _state.asStateFlow()

    private var session: Session? = null
    private var generation = 0

    /** The draft the canvas renders while the sheet is open, or null for the entry document. */
    val draftDocument: EditDocument? get() = session?.draft?.document

    private class Session(val generation: Int, val entry: EditDocument) {
        val insertIndex = entry.skinRetouchInsertIndex()
        val baseDocument = entry.skinRetouchBase(insertIndex)
        var faces: List<DetectedFace> = emptyList()
        var detail: FaceDetail? = null
        var roiBase: Bitmap? = null
        var previewBase: Bitmap? = null
        var previewScale = 1f
        var faceGeneration = 0
        var requestGeneration = 0
        val candidates = mutableMapOf<SkinRetouchKind, PreparedCorrection>()
        var draft: Draft? = null
        var draftRevision = 0

        /** The local face analysis. A settings save never cancels it; it is not a server request. */
        var analysisJob: Job? = null
        var job: Job? = null
        var draftJob: Job? = null
    }

    private class Draft(val document: EditDocument, val resultRef: ImageRef, val maskRef: ImageRef)

    init {
        scope.launch {
            combine(provider.checking, provider.availability, provider.supportedKinds) { checking, available, kinds ->
                serverStatusOf(checking, available) to kinds
            }.collect { (status, kinds) ->
                _state.value = _state.value.copy(server = status, serverKinds = kinds)
            }
        }
        scope.launch { serverHost.collect { _state.value = _state.value.copy(serverHost = it) } }
        // A save — including one of the same values — is new settings: what was asked of the old
        // server is cancelled and its candidates are no longer this session's (§7).
        scope.launch { serverSaves.drop(1).collect { onSettingsChanged() } }
    }

    /** Entering the sheet analyses locally; nothing is uploaded until 미리보기 (skin_retouch.md §6). */
    fun open(document: EditDocument?) {
        close()
        document ?: return
        val opened = Session(++generation, document)
        session = opened
        _state.value = _state.value.copy(analysis = SkinAnalysis.Analyzing)
        opened.analysisJob = scope.launch { analyse(opened, faceId = null) }
    }

    /** The explicit retry after "얼굴 분석을 준비하지 못했어요" (§5). */
    fun retryAnalysis() {
        val current = session ?: return
        if (_state.value.busy) return
        current.analysisJob?.cancel()
        _state.value = _state.value.copy(analysis = SkinAnalysis.Analyzing, message = null)
        current.analysisJob = scope.launch { analyse(current, faceId = _state.value.selectedFaceId) }
    }

    /** §3: another face is a new selection — requests and candidates go, the four values reset. */
    fun selectFace(id: String) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || id == state.selectedFaceId || current.faces.none { it.id == id }) return
        current.analysisJob?.cancel()
        current.job?.cancel()
        resetCandidates(current)
        current.faceGeneration++
        _state.value = _state.value.copy(
            selectedFaceId = id,
            analysis = SkinAnalysis.Analyzing,
            strengths = SkinRetouchState.ZERO_STRENGTHS,
            faceSupport = emptyMap(),
        )
        current.analysisJob = scope.launch { analyse(current, faceId = id) }
    }

    fun refreshServer() = provider.refresh()

    /** §4: a prepared kind composites locally; an unprepared one waits for 미리보기. */
    fun setStrength(kind: SkinRetouchKind, value: Int) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || !state.kindEnabled(kind)) return
        val clamped = value.coerceIn(0, SkinRetouchState.MAX_STRENGTH)
        _state.value = state.copy(strengths = state.strengths + (kind to clamped))
        recomposeDraft(current)
    }

    /** §4: only the kinds above zero that have no candidate yet are asked for, one request each. */
    fun preview() {
        val current = session ?: return
        val state = _state.value
        val detail = current.detail
        val roiBase = current.roiBase
        if (!state.canPreview || detail == null || roiBase == null) return
        val needed = SkinRetouchKind.entries.filter {
            (state.strengths[it] ?: 0) > 0 && state.kindEnabled(it) && it !in current.candidates
        }
        val request = ++current.requestGeneration
        val face = current.faceGeneration
        _state.value = state.copy(preparing = true, message = null)
        current.job = scope.launch {
            try {
                prepareAll(current, detail, roiBase, needed, face, request)
            } finally {
                if (isCurrent(current, face, request)) _state.value = _state.value.copy(preparing = false)
            }
            if (isCurrent(current, face, request)) recomposeDraft(current)
        }
    }

    /** §6: one commit of the prepared result. No inference happens here. */
    fun apply() {
        val current = session ?: return
        val detail = current.detail
        val roiBase = current.roiBase
        if (!_state.value.canApply || detail == null || roiBase == null) return
        val face = current.faceGeneration
        val request = ++current.requestGeneration
        val strengths = _state.value.strengths
        _state.value = _state.value.copy(applying = true, message = null)
        current.job = scope.launch {
            try {
                commit(current, detail, roiBase, strengths, face, request)
            } finally {
                if (isCurrent(current, face, request)) _state.value = _state.value.copy(applying = false)
            }
        }
    }

    /** DESIGN.md §7: the overlay's 취소. The sheet stays; nothing was committed. */
    fun cancelWork() {
        val current = session ?: return
        if (!_state.value.busy) return
        current.job?.cancel()
        current.requestGeneration++
        _state.value = _state.value.copy(preparing = false, applying = false)
    }

    /** 취소, Back, dismiss, or a document change: the draft and every request go (§5). */
    fun close() {
        val current = session ?: return
        session = null
        generation++
        current.analysisJob?.cancel()
        current.job?.cancel()
        current.draftJob?.cancel()
        current.draft?.let { removeDraft(it) }
        current.draft = null
        _state.value = SkinRetouchState(
            server = _state.value.server,
            serverKinds = _state.value.serverKinds,
            serverHost = _state.value.serverHost,
        )
    }

    /** §5: Undo/Redo/Reset under an open sheet end the session. True when one was open. */
    fun onDocumentChanged(document: EditDocument?): Boolean {
        val current = session
        val changed = current != null && document != current.entry
        if (changed) close()
        return changed
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- analysis -----------------------------------------------------------------------------

    @Suppress("ReturnCount")
    private suspend fun analyse(current: Session, faceId: String?) {
        val face = current.faceGeneration
        val base = host.renderBase(current.baseDocument)
        if (!isCurrent(current, face)) return
        if (base == null) return failAnalysis(current)
        if (current.faces.isEmpty()) {
            when (val analysis = analyzer.analyze(base)) {
                is Result.Failure -> return failAnalysis(current)
                is Result.Success -> {
                    if (!isCurrent(current, face)) return
                    if (analysis.value.faces.isEmpty()) {
                        _state.value = _state.value.copy(analysis = SkinAnalysis.NoFace)
                        return
                    }
                    current.faces = analysis.value.faces
                    current.previewScale = minOf(1f, previewLongEdgePx.toFloat() / maxOf(base.width, base.height))
                    current.previewBase = scaled(base, current.previewScale)
                    _state.value = _state.value.copy(faces = current.faces.map { thumbnailOf(base, it) })
                }
            }
        }
        val selected = current.faces.firstOrNull { it.id == faceId } ?: current.faces.first()
        val roi = Bitmap.createBitmap(
            base,
            selected.roi.left,
            selected.roi.top,
            selected.roi.width(),
            selected.roi.height(),
        ).copy(Bitmap.Config.ARGB_8888, false)
        val detail = analyzer.detail(roi, selected)
        if (!isCurrent(current, face)) return
        when (detail) {
            is Result.Failure -> failAnalysis(current)
            is Result.Success -> {
                current.detail = detail.value
                current.roiBase = roi
                _state.value = _state.value.copy(
                    analysis = SkinAnalysis.Ready,
                    selectedFaceId = selected.id,
                    faceSupport = detail.value.support,
                )
            }
        }
    }

    private fun failAnalysis(current: Session) {
        if (session === current) _state.value = _state.value.copy(analysis = SkinAnalysis.Failed)
    }

    private fun thumbnailOf(base: Bitmap, face: DetectedFace): SkinFaceChoice {
        val box = face.bounds
        val crop = Bitmap.createBitmap(
            base,
            box.left,
            box.top,
            box.width().coerceAtLeast(1),
            box.height().coerceAtLeast(1),
        )
        val scale = THUMBNAIL_PX.toFloat() / maxOf(crop.width, crop.height)
        return SkinFaceChoice(face.id, scaled(crop, minOf(1f, scale)).asImageBitmap())
    }

    // ---- requests -----------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private suspend fun prepareAll(
        current: Session,
        detail: FaceDetail,
        roiBase: Bitmap,
        kinds: List<SkinRetouchKind>,
        face: Int,
        request: Int,
    ) {
        val others = current.faces.filter { it.id != detail.face.id }
        for (kind in kinds) {
            val allowed = withContext(dispatchers.default) {
                SkinAllowedMask.bitmap(kind, detail, others)
            }
            val result = provider.prepare(roiBase, allowed, kind)
            // A provider that ignored cancellation answers here too; it writes nothing (§7).
            if (!isCurrent(current, face, request)) return
            when (result) {
                is Result.Failure -> {
                    _state.value = _state.value.copy(message = prepareMessageFor(result.error))
                    return
                }
                is Result.Success -> {
                    current.candidates[kind] = result.value
                    _state.value = _state.value.copy(
                        outcomes = _state.value.outcomes + (kind to result.value.outcome),
                        message = if (result.value.outcome == CorrectionOutcome.NoChange) {
                            R.string.skin_retouch_no_change
                        } else {
                            _state.value.message
                        },
                    )
                }
            }
        }
    }

    @Suppress("LongParameterList", "ReturnCount")
    private suspend fun commit(
        current: Session,
        detail: FaceDetail,
        roiBase: Bitmap,
        strengths: Map<SkinRetouchKind, Int>,
        face: Int,
        request: Int,
    ) {
        val layers = layersFor(current, strengths)
        val base = host.renderBase(current.baseDocument)
        if (!isCurrent(current, face, request)) return
        if (base == null) {
            _state.value = _state.value.copy(message = R.string.skin_retouch_save_failed)
            return
        }
        val (result, support) = withContext(dispatchers.default) {
            val roiResult = SkinRetouchComposite.composite(roiBase, layers)
            val roiSupport = SkinRetouchComposite.support(roiBase, layers)
            SkinRetouchComposite.pasteIntoCanvas(base, detail.face.roi, roiResult) to
                SkinRetouchComposite.canvasSupport(base.width, base.height, detail.face.roi, roiSupport)
        }
        val retouchId = newId()
        val maskId = newId()
        val saved = try {
            host.save(retouchId, maskId, result, support)
        } catch (cancelled: CancellationException) {
            // Written and then cancelled on the way back: the files belong to nobody.
            withContext(NonCancellable) { host.discard(retouchId, maskId) }
            throw cancelled
        }
        if (saved is Result.Failure) {
            if (isCurrent(current, face, request)) {
                _state.value = _state.value.copy(message = R.string.skin_retouch_save_failed)
            }
            return
        }
        val files = (saved as Result.Success).value
        // §7: checked again right before the commit — a document change or a cancel while the
        // files were being written means they belong to nobody.
        if (!isCurrent(current, face, request)) {
            withContext(NonCancellable) { host.discard(retouchId, maskId) }
            return
        }
        val committed = current.entry.withSkinRetouch(
            maskRef = files.maskRef,
            resultRef = files.resultRef,
            settings = settingsFor(current, strengths),
            maskId = maskId,
            id = retouchId,
            insertIndex = current.insertIndex,
        )
        close()
        host.commit(committed)
    }

    private fun layersFor(current: Session, strengths: Map<SkinRetouchKind, Int>): List<SkinRetouchLayer> =
        SkinRetouchKind.entries.mapNotNull { kind ->
            val value = strengths[kind] ?: 0
            val candidate = current.candidates[kind]
            if (value <= 0 || candidate == null || candidate.outcome != CorrectionOutcome.Corrected) {
                null
            } else {
                SkinRetouchLayer(
                    candidate.candidate,
                    candidate.changeSupport,
                    value / SkinRetouchState.MAX_STRENGTH.toFloat(),
                )
            }
        }

    /** §6: all four strengths, and the engine of every kind that is above zero. */
    private fun settingsFor(current: Session, strengths: Map<SkinRetouchKind, Int>) = SkinRetouchSettings(
        strengths = SkinRetouchKind.entries.associate { kind ->
            kind.document to ((strengths[kind] ?: 0) / SkinRetouchState.MAX_STRENGTH.toFloat())
        },
        engines = SkinRetouchKind.entries.mapNotNull { kind ->
            val candidate = current.candidates[kind]
            if ((strengths[kind] ?: 0) > 0 && candidate != null) {
                kind.document to "${candidate.engineVersion};mask=${SkinAllowedMask.SKIN_MASK_VERSION}"
            } else {
                null
            }
        }.toMap(),
    )

    // ---- draft --------------------------------------------------------------------------------

    /** §5: conflated latest-wins; a missing candidate keeps the old draft but disables 적용. */
    private fun recomposeDraft(current: Session) {
        val strengths = _state.value.strengths
        val needsPreview = _state.value.needsPreview
        if (needsPreview) {
            _state.value = _state.value.copy(draftReady = false)
            return
        }
        current.draftJob?.cancel()
        val revision = ++current.draftRevision
        current.draftJob = scope.launch {
            val detail = current.detail ?: return@launch
            val roiBase = current.roiBase ?: return@launch
            val previewBase = current.previewBase ?: return@launch
            val layers = layersFor(current, strengths)
            val pixels = if (layers.isEmpty()) {
                null
            } else {
                withContext(dispatchers.default) { draftPixelsOf(current, detail, roiBase, previewBase, layers) }
            }
            // Only a result still wanted reaches the renderer, and only from here: a cancelled or
            // stale composite never registered anything, so there is nothing of it to clean up.
            if (session !== current || current.draftRevision != revision) return@launch
            val draft = pixels?.let { (result, support) -> publishDraft(current, result, support, strengths, revision) }
            current.draft?.let { removeDraft(it) }
            current.draft = draft
            _state.value = _state.value.copy(draftReady = true)
            host.onDraftChanged()
        }
    }

    /** The preview-scale result and support. Pure pixels: nothing is handed to the renderer here. */
    private fun draftPixelsOf(
        current: Session,
        detail: FaceDetail,
        roiBase: Bitmap,
        previewBase: Bitmap,
        layers: List<SkinRetouchLayer>,
    ): Pair<Bitmap, Bitmap> {
        val roiResult = SkinRetouchComposite.composite(roiBase, layers)
        val roiSupport = SkinRetouchComposite.support(roiBase, layers)
        val roi = detail.face.roi
        val scale = current.previewScale
        val target = Rect(
            (roi.left * scale).roundToInt(),
            (roi.top * scale).roundToInt(),
            (roi.left * scale).roundToInt() + maxOf(1, (roi.width() * scale).roundToInt()),
            (roi.top * scale).roundToInt() + maxOf(1, (roi.height() * scale).roundToInt()),
        ).apply { intersect(0, 0, previewBase.width, previewBase.height) }
        val result = SkinRetouchComposite.pasteIntoCanvas(
            previewBase,
            target,
            Bitmap.createScaledBitmap(roiResult, target.width(), target.height(), true),
        )
        val support = SkinRetouchComposite.canvasSupport(
            previewBase.width,
            previewBase.height,
            target,
            Bitmap.createScaledBitmap(roiSupport, target.width(), target.height(), false),
        )
        return result to support
    }

    private fun publishDraft(
        current: Session,
        result: Bitmap,
        support: Bitmap,
        strengths: Map<SkinRetouchKind, Int>,
        revision: Int,
    ): Draft {
        val prefix = "$TRANSIENT_SCHEME${current.generation}/$revision"
        val resultRef = ImageRef("$prefix/result")
        val maskRef = ImageRef("$prefix/mask")
        host.putTransient(resultRef, result)
        host.putTransient(maskRef, support)
        val document = current.entry.withSkinRetouch(
            maskRef = maskRef,
            resultRef = resultRef,
            settings = settingsFor(current, strengths),
            maskId = "$DRAFT_ID-mask",
            id = DRAFT_ID,
            insertIndex = current.insertIndex,
        )
        return Draft(document, resultRef, maskRef)
    }

    private fun removeDraft(draft: Draft) {
        host.removeTransient(draft.resultRef)
        host.removeTransient(draft.maskRef)
    }

    private fun resetCandidates(current: Session) {
        current.requestGeneration++
        current.candidates.clear()
        current.draftJob?.cancel()
        current.draft?.let { removeDraft(it) }
        current.draft = null
        _state.value = _state.value.copy(outcomes = emptyMap(), preparing = false, draftReady = false)
        host.onDraftChanged()
    }

    private fun onSettingsChanged() {
        val current = session ?: return
        if (_state.value.applying) return
        current.job?.cancel()
        resetCandidates(current)
    }

    private fun isCurrent(current: Session, face: Int, request: Int? = null): Boolean =
        session === current && current.faceGeneration == face &&
            (request == null || current.requestGeneration == request) &&
            host.currentDocument() == current.entry

    private fun scaled(bitmap: Bitmap, scale: Float): Bitmap =
        if (scale >= 1f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * scale).roundToInt()),
                maxOf(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        }

    private val SkinRetouchKind.document: RetouchKind
        get() = when (this) {
            SkinRetouchKind.Blemish -> RetouchKind.Blemish
            SkinRetouchKind.Shine -> RetouchKind.Shine
            SkinRetouchKind.DarkCircles -> RetouchKind.DarkCircles
            SkinRetouchKind.ShavingShadow -> RetouchKind.ShavingShadow
        }

    private companion object {
        const val THUMBNAIL_PX = 96
        const val TRANSIENT_SCHEME = "transient://skin-retouch/"
        const val DRAFT_ID = "skin-retouch-draft"
    }
}
