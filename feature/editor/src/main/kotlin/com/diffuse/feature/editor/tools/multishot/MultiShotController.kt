package com.diffuse.feature.editor.tools.multishot

import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asImageBitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HeroMask
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MAX_SHOTS
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.SHOT_ROTATION_RANGE
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.TIMELINE_DISTANCE_RANGE
import com.diffuse.core.imaging.model.Timeline
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.TimelineLayout
import com.diffuse.core.imaging.model.multiShotBase
import com.diffuse.core.imaging.model.withMultiShot
import com.diffuse.core.imaging.render.CanvasPoint
import com.diffuse.core.imaging.render.MultiShotLayout
import com.diffuse.core.imaging.render.MultiShotSubject
import com.diffuse.feature.editor.tools.multishot.MultiShotState.Companion.HERO_KEY
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.select.MaskOps
import com.diffuse.feature.editor.tools.select.MaskOutline
import com.diffuse.feature.editor.tools.select.MergeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the controller needs from the editor: the picked photo, the project's files and history. */
interface MultiShotHost {

    /** The picked photograph at working size, EXIF-upright (`ImageLoader`, specs/imaging.md). */
    suspend fun loadPhoto(uri: Uri): Result<Bitmap>

    /** Writes one subject as `shot_<fileId>.png`; nothing is left behind on failure. */
    suspend fun saveSubject(fileId: String, subject: Bitmap): Result<ImageRef>

    /** Deletes this session's own subject files; never ones the saved document references. */
    suspend fun discardSubjects(fileIds: List<String>)

    /** A stored subject, small, for its thumbnail; null when it will not decode. */
    suspend fun thumbnailOf(ref: ImageRef, longEdgePx: Int): Bitmap?

    /**
     * §6: [document] rendered at preview size — the composite's input canvas, on which the last
     * moment is selected. Local; nothing is sent. Null when it will not render.
     */
    suspend fun renderBase(document: EditDocument): Bitmap?

    /** §8: the selection tool's SAM 3 session goes before this tool opens its own. */
    suspend fun releaseSelection()

    /** The document on screen now, for the checks before a late answer may write anything. */
    fun currentDocument(): EditDocument?

    /** One history entry, then the sheet closes. */
    fun commit(document: EditDocument)
}

/**
 * specs/multishot.md §2, §8. The current photo is the background; one or two added photographs
 * are imported, their subject extracted with SAM 3 only on an explicit 피사체 추출, placed, and
 * committed as one `MultiShot` in one history step.
 *
 * Every asynchronous step carries the session and a per-photo request token. A late answer — from
 * a provider that ignored cancellation, after a delete, a replacement, a cancel or a document
 * change — is checked when it arrives and dropped rather than written. Files this session wrote
 * are its own until 적용 hands the committed ones to the document; the rest are discarded on close.
 */
// One tool, one object: import, extraction, refinement, placement and commit share the session.
// Every entry point opens with the same guard clauses (session, selection, busy), hence ReturnCount.
// open/publish/saveSubject branch once per layout and per hero-or-photo; splitting them would
// scatter one session's bookkeeping, hence the complexity suppressions.
@Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
    "ReturnCount",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
)
class MultiShotController(
    private val segmentation: SegmentationProvider,
    settingsChanges: Flow<Any>,
    serverHost: Flow<String>,
    private val host: MultiShotHost,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) {

    private val _state = MutableStateFlow(MultiShotState())
    val state: StateFlow<MultiShotState> = _state.asStateFlow()

    private var session: Session? = null

    /** [uri] is null for the hero's input canvas, which is rendered rather than picked. */
    private class Photo(val uri: Uri?, val preview: Bitmap, val widthPx: Int, val heightPx: Int)

    /** Per photograph, the parts that are not UI state. */
    private class Entry(val key: String) {
        var request = 0
        var photo: Photo? = null
        var subjectRef: ImageRef? = null
        var widthPx = 0
        var heightPx = 0
    }

    private class Session(val entry: EditDocument) {
        /** A new composite's id for the whole session, so the same draft is the same document. */
        val draftId = newId()
        val entries = mutableMapOf<String, Entry>()

        /** Subjects this session wrote, by file id, with the ref once the write returned. */
        val owned = mutableMapOf<String, ImageRef?>()
        var seg: SegSession? = null
        var segKey: String? = null
        var baseMask: Bitmap? = null
        var candidates: List<Bitmap> = emptyList()
        var job: Job? = null
        var work = 0
        var restore: (() -> Unit)? = null
        var thumbnails: Job? = null
        var base: Job? = null

        /** The Photo Picker launch whose answer is still expected; any other answer is dropped. */
        var pick: PickRequest? = null

        /** The run under way — a fresh object per run, so a step of one that ended goes no further. */
        var run: Any? = null

        /** The run's photographs: the hero first, then the added photos in the order shown. */
        var runKeys: List<String> = emptyList()
    }

    init {
        scope.launch {
            segmentation.availability.collect { availability ->
                _state.value = _state.value.copy(server = serverOf(availability))
            }
        }
        scope.launch { serverHost.collect { _state.value = _state.value.copy(serverHost = it) } }
        // §8: new server settings end what was asked of the old server.
        scope.launch { settingsChanges.drop(1).collect { onSettingsChanged() } }
    }

    /**
     * The draft the canvas renders while the sheet is open, or null when no sheet is. The time
     * layout draws in time order and protects its hero once it has one; until then it is drawn
     * the free way, in time order, with nothing protected.
     */
    fun draftDocument(): EditDocument? {
        val current = session ?: return null
        val state = _state.value
        val timeline = draftTimeline(current, state)
        val id = current.draftId
        return when {
            !state.timeline -> current.entry.withMultiShot(state.draftShots, id, timeline = timeline)
            timeline?.hero != null && state.draftShots.all { it.anchor != null } ->
                current.entry.withMultiShot(state.draftShots, id, MultiShotMode.Timeline, timeline)
            else -> current.entry.withMultiShot(
                state.draftShots.sortedBy { shot -> state.timeOrder.indexOf(shot.id) },
                id,
            )
        }
    }

    /**
     * §2 requirement 10: an existing composite comes back exactly — its layout, shots, order,
     * transforms, opacity, and for the time layout its confirmed order, settings and hero; nothing
     * is segmented and nothing is laid out again. A free composite saved before the time layout
     * existed offers its drawing order as the time order, unconfirmed (§7).
     */
    fun open(document: EditDocument?) {
        close()
        document ?: return
        val opened = Session(document)
        session = opened
        val composite = document.multiShot()
        val existing = composite?.shots.orEmpty()
        existing.forEach { shot ->
            opened.entries[shot.id] = Entry(shot.id).apply {
                subjectRef = shot.subjectRef
                widthPx = shot.widthPx
                heightPx = shot.heightPx
            }
        }
        val timeline = composite?.timeline
        val hero = timeline?.hero
        if (hero != null) {
            opened.entries[HERO_KEY] = Entry(HERO_KEY).apply {
                subjectRef = hero.ref
                widthPx = hero.widthPx
                heightPx = hero.heightPx
            }
        }
        val mode = composite?.mode
        _state.value = MultiShotState(
            open = true,
            mode = mode,
            items = existing.map { ShotItem(it.id, ShotStatus.Ready, placement = it.placement, anchor = it.anchor) },
            selectedKey = existing.firstOrNull()?.id,
            server = _state.value.server,
            serverHost = _state.value.serverHost,
            hadComposite = existing.isNotEmpty(),
            timeOrder = timeline?.order ?: existing.map { it.id },
            orderConfirmed = timeline?.orderConfirmed == true,
            laidOutOrder = timeline?.order?.takeIf { mode == MultiShotMode.Timeline },
            hero = hero?.let { ShotItem(HERO_KEY, ShotStatus.Ready) },
            heroAnchor = hero?.anchor,
            canvasWidth = hero?.widthPx ?: 0,
            canvasHeight = hero?.heightPx ?: 0,
            layout = timeline?.layout ?: TimelineLayout(),
        )
        publish()
        if (mode == MultiShotMode.Timeline) prepareHero(opened)
        opened.thumbnails = scope.launch {
            existing.forEach { shot ->
                // One preview-sized decode at a time: the thumbnail and the subject's bounds.
                val decoded = host.thumbnailOf(shot.subjectRef, PHOTO_PREVIEW_PX) ?: return@forEach
                val (thumbnail, bounds) = withContext(dispatchers.default) {
                    scaled(decoded, THUMBNAIL_PX) to MultiShotLayout.bounds(decoded)
                }
                if (session !== opened) return@launch
                updateItem(shot.id) {
                    it.copy(
                        thumbnail = thumbnail.asImageBitmap(),
                        bounds = bounds,
                        anchor = it.anchor ?: bounds?.let(MultiShotLayout::anchorOf),
                    )
                }
                publish()
            }
        }
    }

    /**
     * §2.1: the layout of a new composite is chosen explicitly, and a stored one changes only when
     * the user switches it. Switching keeps every shot, transform and the confirmed order.
     */
    fun setMode(mode: MultiShotMode) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || state.mode == mode) return
        _state.value = state.copy(mode = mode, anchorEditing = false)
        if (mode == MultiShotMode.Timeline) {
            prepareHero(current)
        } else if (_state.value.selectedKey == HERO_KEY) {
            closeSegmentation(current)
            _state.value = _state.value.copy(selectedKey = _state.value.items.firstOrNull()?.key)
        }
        publish()
        showSelected()
    }

    /**
     * §6 requirement 10: the hero is selected on the composite's own input — the canvas before it,
     * without the `Crop` — rendered locally. A hero already stored stays [ShotStatus.Ready].
     */
    private fun prepareHero(current: Session) {
        if (_state.value.hero == null) {
            _state.value = _state.value.copy(hero = ShotItem(HERO_KEY, ShotStatus.Importing))
        }
        if (current.base != null) return
        val entry = current.entries.getOrPut(HERO_KEY) { Entry(HERO_KEY) }
        current.base = scope.launch {
            val rendered = host.renderBase(current.entry.multiShotBase()) ?: run {
                if (session === current) {
                    updateItem(HERO_KEY) {
                        it.copy(status = ShotStatus.Picked, problem = R.string.multishot_hero_input_failed)
                    }
                }
                return@launch
            }
            val thumbnail = withContext(dispatchers.default) { scaled(rendered, THUMBNAIL_PX) }
            if (session !== current) return@launch
            entry.photo = Photo(null, rendered, rendered.width, rendered.height)
            updateItem(HERO_KEY) {
                it.copy(
                    status = if (it.status == ShotStatus.Importing) ShotStatus.Picked else it.status,
                    thumbnail = thumbnail.asImageBitmap(),
                )
            }
            if (_state.value.canvasWidth == 0) {
                _state.value = _state.value.copy(canvasWidth = rendered.width, canvasHeight = rendered.height)
            }
            showSelected()
        }
    }

    /** §6: the stored hero is selected again, on the same input canvas; the old mask stays until 추출 완료. */
    fun reselectHero() {
        val current = session ?: return
        val state = _state.value
        if (state.busy || state.hero?.status != ShotStatus.Ready) return
        if (current.entries[HERO_KEY]?.photo == null) return
        _state.value = state.copy(selectedKey = HERO_KEY, anchorEditing = false)
        updateItem(HERO_KEY) { it.copy(status = ShotStatus.Picked) }
        publish()
        showSelected()
    }

    /** 취소, Back, a document change or leaving the editor: every request and unowned file goes. */
    fun close() {
        val current = session ?: return
        session = null
        current.job?.cancel()
        current.thumbnails?.cancel()
        current.base?.cancel()
        val seg = current.seg
        current.seg = null
        val owned = current.owned.keys.toList()
        if (seg != null || owned.isNotEmpty()) {
            scope.launch(NonCancellable) {
                seg?.let { segmentation.close(it) }
                if (owned.isNotEmpty()) host.discardSubjects(owned)
            }
        }
        _state.value = MultiShotState(server = _state.value.server, serverHost = _state.value.serverHost)
    }

    /** Undo/Redo/Reset under an open sheet end the session. True when one was open. */
    fun onDocumentChanged(document: EditDocument?): Boolean {
        val current = session
        val changed = current != null && document != current.entry
        if (changed) close()
        return changed
    }

    // ---- photographs -------------------------------------------------------------------------

    /**
     * The route is about to open the Photo Picker: a replacement is always one photo; new photos
     * are as many as still fit in the time layout and one at a time in the free layout. Null when
     * no pick may start — the answer to a request is only taken while it is this session's last.
     */
    fun requestPick(replaceKey: String?): PickRequest? {
        val current = session ?: return null
        val state = _state.value
        if (state.busy || state.mode == null) return null
        val max = when {
            replaceKey != null -> 1.takeIf { state.items.any { it.key == replaceKey } }
            state.items.size >= MAX_SHOTS -> null
            state.timeline -> MAX_SHOTS - state.items.size
            else -> 1
        } ?: return null
        return PickRequest(newId(), replaceKey, max).also { current.pick = it }
    }

    /**
     * The Photo Picker answered [requestId] with [uris], in the order it returned them — the order
     * the timeline shows, never re-sorted or de-duplicated. An empty answer is the user backing
     * out. A list longer than the request allowed is refused whole, never cut short.
     */
    fun onPicked(requestId: String?, uris: List<Uri>) {
        val current = session ?: return
        val request = current.pick?.takeIf { it.id == requestId } ?: return
        current.pick = null
        if (uris.isEmpty() || _state.value.busy) return
        val replaceKey = request.replaceKey
        if (uris.size > request.max || (replaceKey == null && _state.value.items.size + uris.size > MAX_SHOTS)) {
            _state.value = _state.value.copy(message = R.string.multishot_too_many)
            return
        }
        when {
            replaceKey != null || uris.size == 1 -> onPhotoPicked(uris.single(), replaceKey)
            else -> importAll(current, uris)
        }
    }

    /**
     * Several new photographs, read one after another. One that will not read stays in its place,
     * named and [ShotStatus.Unreadable], for 교체 or 삭제; the others are read regardless.
     */
    private fun importAll(current: Session, uris: List<Uri>) {
        closeSegmentation(current)
        val added = uris.map { uri -> Entry(newId()).also { current.entries[it.key] = it } to uri }
        val keys = added.map { it.first.key }
        val tokens = added.map { (entry, _) -> ++entry.request }
        val state = _state.value
        _state.value = state.copy(
            items = state.items + keys.map { ShotItem(it, ShotStatus.Importing) },
            timeOrder = state.timeOrder + keys,
            selectedKey = keys.first(),
            panel = TimelinePanel.Photos,
            anchorEditing = false,
            orderConfirmed = false,
        )
        publish()
        startWork(current, R.string.multishot_importing, restore = { dropImporting(current, keys) }) {
            added.forEachIndexed { index, (entry, uri) ->
                importPhoto(current, entry, tokens[index], uri, before = null, keepUnreadable = true)
            }
        }
    }

    /** A cancelled batch import: the photos not yet read go; the ones read stay. */
    private fun dropImporting(current: Session, keys: List<String>) {
        val state = _state.value
        val gone = state.items.filter { it.key in keys && it.status == ShotStatus.Importing }.map { it.key }.toSet()
        gone.forEach { key -> current.entries.remove(key)?.let { it.request++ } }
        val items = state.items.filterNot { it.key in gone }
        _state.value = state.copy(
            items = items,
            timeOrder = state.timeOrder.filterNot { it in gone },
            selectedKey = if (state.selectedKey in gone) items.lastOrNull()?.key else state.selectedKey,
        )
        publish()
        showSelected()
    }

    /**
     * The Photo Picker answered. A null [uri] is the user backing out: the draft stays exactly as
     * it was. [replaceKey] names the photograph being replaced, or null for a new one.
     */
    fun onPhotoPicked(uri: Uri?, replaceKey: String?) {
        val current = session ?: return
        if (uri == null || _state.value.busy || _state.value.mode == null) return
        val replacing = replaceKey != null
        if (replacing && _state.value.items.none { it.key == replaceKey }) return
        if (!replacing && _state.value.items.size >= MAX_SHOTS) return
        val key = replaceKey ?: newId()
        val entry = current.entries.getOrPut(key) { Entry(key) }
        val token = ++entry.request
        // REVIEW R2: whichever photo had the open selection — this one or another — goes back to a
        // retryable photo; a Selecting item never outlives its session.
        closeSegmentation(current)
        val state = _state.value
        val before = state.items.firstOrNull { it.key == key }
        _state.value = if (before == null) {
            state.copy(items = state.items + ShotItem(key, ShotStatus.Importing), timeOrder = state.timeOrder + key)
        } else {
            state.copy(items = state.items.map { if (it.key == key) it.copy(status = ShotStatus.Importing) else it })
        }.copy(selectedKey = key, anchorEditing = false)
        publish()
        startWork(current, R.string.multishot_importing, restore = { restoreItem(key, before) }) {
            importPhoto(current, entry, token, uri, before)
        }
    }

    private suspend fun importPhoto(
        current: Session,
        entry: Entry,
        token: Int,
        uri: Uri,
        before: ShotItem?,
        keepUnreadable: Boolean = false,
    ) {
        val loaded = host.loadPhoto(uri)
        if (!isCurrent(current, entry.key, token)) return
        val working = when (loaded) {
            is Result.Failure -> {
                if (keepUnreadable) {
                    val problem = importMessage(loaded.error)
                    updateItem(entry.key) { it.copy(status = ShotStatus.Unreadable, problem = problem) }
                } else {
                    restoreItem(entry.key, before)
                }
                _state.value = _state.value.copy(message = importMessage(loaded.error))
                return
            }
            is Result.Success -> loaded.value
        }
        val preview = withContext(dispatchers.default) { scaled(working, PHOTO_PREVIEW_PX) }
        val thumbnail = withContext(dispatchers.default) { scaled(working, THUMBNAIL_PX) }
        if (!isCurrent(current, entry.key, token)) return
        entry.photo = Photo(uri, preview, working.width, working.height)
        entry.subjectRef = null
        updateItem(entry.key) {
            it.copy(
                status = ShotStatus.Picked,
                thumbnail = thumbnail.asImageBitmap(),
                problem = null,
                anchor = null,
                bounds = null,
            )
        }
        // §2.1 requirement 4: new content means the order has to be confirmed again.
        _state.value = _state.value.copy(orderConfirmed = false)
        publish()
        showSelected()
    }

    fun select(key: String) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || (state.items.none { it.key == key } && (key != HERO_KEY || state.hero == null))) return
        if (current.segKey != null && current.segKey != key) closeSegmentation(current)
        // A hero picked again but left unfinished keeps the mask it already had.
        if (key != HERO_KEY && _state.value.hero?.status == ShotStatus.Picked &&
            current.entries[HERO_KEY]?.subjectRef != null
        ) {
            updateItem(HERO_KEY) { it.copy(status = ShotStatus.Ready, problem = null) }
        }
        _state.value = _state.value.copy(selectedKey = key, panel = TimelinePanel.Photos)
        publish()
        showSelected()
    }

    /** §2 requirement 3: a photo is removed outright; its file, if this session wrote one, goes on close. */
    fun remove(key: String) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || state.items.none { it.key == key }) return
        if (current.segKey == key) closeSegmentation(current)
        current.entries.remove(key)?.let { it.request++ }
        val items = _state.value.items.filterNot { it.key == key }
        _state.value = _state.value.copy(
            items = items,
            selectedKey = if (state.selectedKey == key) items.firstOrNull()?.key else state.selectedKey,
            timeOrder = state.timeOrder - key,
            orderConfirmed = false,
        )
        publish()
        showSelected()
    }

    /** §2 requirement 3, the free layout: later in the list is drawn on top. Time order is untouched. */
    fun move(key: String, towardsFront: Boolean) {
        val state = _state.value
        if (session == null || state.busy) return
        val index = state.items.indexOfFirst { it.key == key }
        val target = if (towardsFront) index + 1 else index - 1
        if (index < 0 || target !in state.items.indices) return
        val items = state.items.toMutableList().apply { add(target, removeAt(index)) }
        _state.value = state.copy(items = items)
        publish()
    }

    // ---- time order (§2.1) -------------------------------------------------------------------

    /**
     * §2.1 requirement 3: one step earlier or later in time. Only the order changes — positions and
     * opacity wait for 시간 순서대로 배치 (requirement 5) — and it has to be confirmed again.
     */
    fun moveInTime(key: String, earlier: Boolean) {
        val state = _state.value
        if (session == null || state.busy) return
        val index = state.timeOrder.indexOf(key)
        val target = if (earlier) index - 1 else index + 1
        if (index < 0 || target !in state.timeOrder.indices) return
        val order = state.timeOrder.toMutableList().apply { add(target, removeAt(index)) }
        _state.value = state.copy(timeOrder = order, orderConfirmed = false)
        publish()
    }

    /** §2.1 requirement 3: the whole run of earlier moments, the other way round. */
    fun reverseTime() {
        val state = _state.value
        if (session == null || state.busy || state.timeOrder.size < 2) return
        _state.value = state.copy(timeOrder = state.timeOrder.reversed(), orderConfirmed = false)
        publish()
    }

    // ---- extraction --------------------------------------------------------------------------

    /** §2 requirement 4: the only path that sends the photograph, and only this one. */
    fun extract() {
        val current = session ?: return
        val state = _state.value
        val key = state.selectedKey ?: return
        val entry = current.entries[key] ?: return
        val photo = entry.photo ?: return
        if (!state.canExtract) return
        closeSegmentation(current)
        val token = ++entry.request
        updateItem(key) { it.copy(status = ShotStatus.Extracting, problem = null) }
        showSelected()
        startWork(current, R.string.multishot_extracting, restore = { revertToPicked(key) }) {
            host.releaseSelection()
            // An upload already on the wire still creates a server session, so it always runs to
            // its answer; a session nobody wants any more is closed right after (selection §6).
            val upload = withContext(dispatchers.default) { scaled(photo.preview, PHOTO_PREVIEW_PX) }
            val opened = withContext(NonCancellable) { segmentation.open(upload) }
            if (!isCurrent(current, key, token)) {
                if (opened is Result.Success) withContext(NonCancellable) { segmentation.close(opened.value) }
                return@startWork
            }
            when (opened) {
                is Result.Failure -> failExtraction(key, opened.error)
                is Result.Success -> {
                    current.seg = opened.value
                    current.segKey = key
                    findPerson(current, key, token, opened.value)
                }
            }
        }
    }

    private suspend fun findPerson(current: Session, key: String, token: Int, seg: SegSession) {
        val found = segmentation.byText(seg, PERSON)
        if (!isCurrent(current, key, token)) return
        when (found) {
            is Result.Failure -> {
                closeSegmentation(current)
                failExtraction(key, found.error)
            }
            is Result.Success -> {
                // Only a mask with something in it is a person; an empty one is no answer.
                val people = withContext(dispatchers.default) {
                    found.value.map { it.alpha }.filterNot(MaskOutline::isEmpty)
                }
                if (!isCurrent(current, key, token)) return
                current.baseMask = null
                updateItem(key) {
                    it.copy(
                        status = ShotStatus.Selecting,
                        problem = if (people.isEmpty()) R.string.multishot_not_found else null,
                    )
                }
                offer(current, people)
            }
        }
    }

    /** §2 requirement 5: one answer is taken; several wait for the user to pick one. */
    private suspend fun offer(current: Session, masks: List<Bitmap>) {
        when (masks.size) {
            0 -> Unit
            1 -> {
                setMask(current, merge(current.baseMask, masks.single()))
                current.candidates = emptyList()
                _state.value = _state.value.copy(candidateCount = 0, chosenCandidate = null)
            }
            else -> {
                current.candidates = masks
                _state.value = _state.value.copy(candidateCount = masks.size, chosenCandidate = null)
                setMask(current, current.baseMask)
            }
        }
    }

    fun chooseCandidate(index: Int) {
        val current = session ?: return
        val key = _state.value.selectedKey ?: return
        val entry = current.entries[key] ?: return
        val candidate = current.candidates.getOrNull(index) ?: return
        if (_state.value.working) return
        val token = ++entry.request
        startWork(current, R.string.multishot_refining, restore = {}) {
            val merged = merge(current.baseMask, candidate)
            if (!isCurrent(current, key, token)) return@startWork
            setMask(current, merged)
            _state.value = _state.value.copy(chosenCandidate = index)
        }
    }

    /** §2 requirement 5: a tap adds the region under it, a long-press takes it out. */
    fun addPoint(x: Float, y: Float, include: Boolean) {
        val current = session ?: return
        val key = _state.value.selectedKey ?: return
        val seg = current.seg ?: return
        val entry = current.entries[key] ?: return
        if (_state.value.working || current.segKey != key || !inPhoto(x, y)) return
        val token = ++entry.request
        val base = _state.value.mask
        startWork(current, R.string.multishot_refining, restore = {}) {
            val found = segmentation.byPoints(seg, PointPrompt(listOf(PointF(x, y)), listOf(true)))
            if (!isCurrent(current, key, token)) return@startWork
            when (found) {
                is Result.Failure -> _state.value = _state.value.copy(message = extractionMessage(found.error))
                is Result.Success -> {
                    val merged = withContext(dispatchers.default) {
                        MaskOps.merged(base, found.value.alpha, if (include) MergeMode.Add else MergeMode.Subtract)
                            .takeUnless(MaskOutline::isEmpty)
                    }
                    if (!isCurrent(current, key, token)) return@startWork
                    current.candidates = emptyList()
                    current.baseMask = null
                    _state.value = _state.value.copy(candidateCount = 0, chosenCandidate = null)
                    setMask(current, merged)
                }
            }
        }
    }

    fun setPhrase(phrase: String) {
        _state.value = _state.value.copy(phrase = phrase)
    }

    /** §2 requirement 5: "골프채" — its candidates are offered and the chosen one is added. */
    fun submitPhrase() {
        val current = session ?: return
        val state = _state.value
        val key = state.selectedKey ?: return
        val seg = current.seg ?: return
        val entry = current.entries[key] ?: return
        val phrase = state.phrase.trim()
        if (state.working || current.segKey != key || phrase.isEmpty()) return
        val token = ++entry.request
        val base = state.mask
        startWork(current, R.string.multishot_refining, restore = {}) {
            val found = segmentation.byText(seg, phrase)
            if (!isCurrent(current, key, token)) return@startWork
            when (found) {
                is Result.Failure -> _state.value = _state.value.copy(message = extractionMessage(found.error))
                is Result.Success -> if (found.value.isEmpty()) {
                    _state.value = _state.value.copy(message = R.string.multishot_phrase_not_found)
                } else {
                    current.baseMask = base
                    _state.value = _state.value.copy(phrase = "")
                    offer(current, found.value.map { it.alpha })
                }
            }
        }
    }

    /**
     * §5, §7: the subject is cut at working size, feathered once and written; only then is the shot
     * placeable. The working photo is re-read here rather than held, so at most one is in memory.
     * §6: the hero's mask is written the same way, at its input canvas's size, as the alpha of an
     * opaque bitmap — the subject file format, owned and discarded like a subject.
     */
    fun finishExtraction() {
        val current = session ?: return
        val state = _state.value
        val key = state.selectedKey ?: return
        val entry = current.entries[key] ?: return
        val photo = entry.photo ?: return
        val mask = state.mask ?: return
        if (!state.canFinishExtraction) return
        val token = ++entry.request
        updateItem(key) { it.copy(status = ShotStatus.Saving, problem = null) }
        val restore = { updateItem(key) { it.copy(status = ShotStatus.Selecting) } }
        val run = current.run
        if (run != null) setPhase(RunPhase.Saving)
        startWork(current, R.string.multishot_saving, restore) {
            saveSubject(current, entry, token, photo, mask)
            // A run that waited for this choice goes on by itself; a failed save ends it here.
            if (run != null) {
                if (_state.value.statusOf(key) == ShotStatus.Ready) continueRun(current, run) else endRun(current, run)
            }
        }
    }

    private suspend fun saveSubject(current: Session, entry: Entry, token: Int, photo: Photo, mask: Bitmap) {
        val key = entry.key
        val working = if (photo.uri == null) {
            withContext(dispatchers.default) {
                Bitmap.createBitmap(photo.widthPx, photo.heightPx, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(android.graphics.Color.BLACK) }
            }
        } else {
            val loaded = host.loadPhoto(photo.uri)
            if (!isCurrent(current, key, token)) return
            (loaded as? Result.Success)?.value
        }
        if (working == null || working.width != photo.widthPx || working.height != photo.heightPx) {
            updateItem(key) { it.copy(status = ShotStatus.Selecting, problem = R.string.multishot_reread_failed) }
            return
        }
        val (subject, bounds) = withContext(dispatchers.default) {
            MultiShotSubject.compose(working, mask) to MultiShotLayout.bounds(mask)
        }
        if (!isCurrent(current, key, token)) return
        val fileId = newId()
        current.owned[fileId] = null
        val saved = try {
            host.saveSubject(fileId, subject)
        } catch (cancelled: CancellationException) {
            // Written and then cancelled on the way back: the file belongs to nobody.
            if (session !== current) withContext(NonCancellable) { host.discardSubjects(listOf(fileId)) }
            throw cancelled
        }
        if (saved is Result.Success) current.owned[fileId] = saved.value
        if (!isCurrent(current, key, token)) {
            if (session !== current) withContext(NonCancellable) { host.discardSubjects(listOf(fileId)) }
            return
        }
        when (saved) {
            is Result.Failure -> {
                current.owned.remove(fileId)
                updateItem(key) { it.copy(status = ShotStatus.Selecting, problem = R.string.multishot_save_failed) }
            }
            is Result.Success -> {
                closeSegmentation(current)
                entry.subjectRef = saved.value
                entry.widthPx = photo.widthPx
                entry.heightPx = photo.heightPx
                val anchor = bounds?.let(MultiShotLayout::anchorOf)
                if (key == HERO_KEY) {
                    updateItem(key) { it.copy(status = ShotStatus.Ready, problem = null) }
                    _state.value = _state.value.copy(
                        heroAnchor = anchor,
                        canvasWidth = photo.widthPx,
                        canvasHeight = photo.heightPx,
                    )
                } else {
                    updateItem(key) {
                        it.copy(status = ShotStatus.Ready, problem = null, anchor = anchor, bounds = bounds)
                    }
                }
                publish()
                showSelected()
            }
        }
    }

    /** DESIGN.md §7: the overlay's 취소. The sheet stays; the photo goes back to where it was. */
    fun cancelWork() {
        val current = session ?: return
        if (!_state.value.working) return
        current.job?.cancel()
        current.work++
        _state.value.selectedKey?.let { key -> current.entries[key]?.let { it.request++ } }
        current.restore?.invoke()
        current.restore = null
        _state.value = _state.value.copy(working = false, workLabel = null)
        current.run?.let { endRun(current, it) }
    }

    // ---- 모두 추출하고 자동 배치 ---------------------------------------------------------------

    /**
     * The one press of the time layout's main action: it confirms the order shown and sends
     * what is still unextracted — the hero first, then the added photos in that order — one photo
     * and one SAM 3 session at a time. A photo with exactly one person is taken and saved without a
     * press; none or several stop the run on that photo until the user's choice is finished
     * (추출 완료), after which it goes on by itself. Once everything is saved the shown order is laid
     * out once, here. With nothing left to extract it lays out at once, without the network.
     */
    fun runAll() {
        val current = session ?: return
        val state = _state.value
        if (!state.canRun) return
        if (!state.pending) {
            if (state.placeBlocker == null) layOut(state.copy(orderConfirmed = true))
            return
        }
        val keys = (listOf(HERO_KEY) + state.timeOrder).filterNot { state.isDone(it) }
        val run = Any()
        current.run = run
        current.runKeys = keys
        _state.value = state.copy(
            orderConfirmed = true,
            run = RunProgress(1, keys.size, RunPhase.Extracting),
            panel = TimelinePanel.Photos,
            anchorEditing = false,
        )
        startWork(current, R.string.multishot_extracting, restore = {}) { continueRun(current, run) }
    }

    /** The overlay's 취소 or 진행 취소: the run stops; what it saved stays in the draft, unplaced. */
    fun cancelRun() {
        val current = session ?: return
        if (_state.value.working) cancelWork() else current.run?.let { endRun(current, it) }
    }

    /** Takes the run's photos in turn until one needs the user or fails; then, all done, lays out. */
    private suspend fun continueRun(current: Session, run: Any) {
        while (session === current && current.run === run) {
            val index = current.runKeys.indexOfFirst { !_state.value.isDone(it) }
            if (index < 0) {
                endRun(current, run)
                val state = _state.value
                if (state.placeBlocker == null) layOut(state)
                return
            }
            val key = current.runKeys[index]
            focus(current, key)
            if (!runStep(current, run, key, index + 1)) return
        }
    }

    /** One photo of the run: true once its subject is saved and the run may go on. */
    private suspend fun runStep(current: Session, run: Any, key: String, step: Int): Boolean {
        val total = current.runKeys.size
        if (key == HERO_KEY && current.entries[HERO_KEY]?.photo == null) {
            // The hero's input canvas is still being rendered: wait for it, said as such.
            progress(step, total, RunPhase.Preparing, R.string.multishot_preparing)
            current.base?.join()
            if (session !== current || current.run !== run) return false
        }
        val entry = current.entries[key]
        val photo = entry?.photo
        if (entry == null || photo == null) return endRun(current, run)
        // An open selection on this photo from before: a finished choice is saved, an open one waits.
        if (current.segKey != key) {
            progress(step, total, RunPhase.Extracting, R.string.multishot_extracting)
            if (!extractFor(current, run, entry, photo)) return false
        }
        val state = _state.value
        val mask = state.mask
        if (mask == null || state.candidateCount > 0) {
            if (state.statusOf(key) != ShotStatus.Selecting) return endRun(current, run)
            _state.value = state.copy(run = RunProgress(step, total, RunPhase.Choosing))
            return false
        }
        progress(step, total, RunPhase.Saving, R.string.multishot_saving)
        val token = ++entry.request
        updateItem(key) { it.copy(status = ShotStatus.Saving, problem = null) }
        current.restore = { updateItem(key) { it.copy(status = ShotStatus.Selecting) } }
        saveSubject(current, entry, token, photo, mask)
        if (session !== current || current.run !== run) return false
        return _state.value.statusOf(key) == ShotStatus.Ready || endRun(current, run)
    }

    /** The run's upload and "person" query for one photo; false when it failed or was overtaken. */
    private suspend fun extractFor(current: Session, run: Any, entry: Entry, photo: Photo): Boolean {
        val key = entry.key
        closeSegmentation(current)
        val token = ++entry.request
        updateItem(key) { it.copy(status = ShotStatus.Extracting, problem = null) }
        current.restore = { revertToPicked(key) }
        showSelected()
        host.releaseSelection()
        val upload = withContext(dispatchers.default) { scaled(photo.preview, PHOTO_PREVIEW_PX) }
        val opened = withContext(NonCancellable) { segmentation.open(upload) }
        if (!isCurrent(current, key, token) || current.run !== run) {
            if (opened is Result.Success) withContext(NonCancellable) { segmentation.close(opened.value) }
            return false
        }
        when (opened) {
            is Result.Failure -> failExtraction(key, opened.error)
            is Result.Success -> {
                current.seg = opened.value
                current.segKey = key
                findPerson(current, key, token, opened.value)
            }
        }
        if (!isCurrent(current, key, token)) return false
        return _state.value.statusOf(key) == ShotStatus.Selecting || endRun(current, run)
    }

    /** Ends [run] if it is still the session's; always false, so a step can return it. */
    private fun endRun(current: Session, run: Any): Boolean {
        if (current.run === run) {
            current.run = null
            current.runKeys = emptyList()
            if (session === current) _state.value = _state.value.copy(run = null)
        }
        return false
    }

    /** The run's photo is the selected one: the canvas shows it, its rows are the sheet's. */
    private fun focus(current: Session, key: String) {
        if (current.segKey != null && current.segKey != key) closeSegmentation(current)
        _state.value = _state.value.copy(selectedKey = key, panel = TimelinePanel.Photos, anchorEditing = false)
        publish()
        showSelected()
    }

    private fun progress(step: Int, total: Int, phase: RunPhase, @StringRes label: Int) {
        _state.value = _state.value.copy(run = RunProgress(step, total, phase), workLabel = label)
    }

    private fun setPhase(phase: RunPhase) {
        val run = _state.value.run ?: return
        _state.value = _state.value.copy(run = run.copy(phase = phase))
    }

    fun refreshServer() {
        scope.launch { segmentation.refresh() }
    }

    // ---- placement ---------------------------------------------------------------------------

    fun setOpacity(value: Float) = place { it.copy(opacity = value) }

    fun setScale(value: Float) = place { it.copy(scale = value) }

    fun setRotation(value: Float) = place { it.copy(rotationDeg = value) }

    /**
     * §4: [dx]/[dy] are fractions of the canvas, already taken out of screen space. While the
     * anchor is being corrected (§4.2 requirement 14) the drag moves the anchor and the subject
     * stays: the hero's anchor directly on the canvas, a shot's back through its transform.
     */
    fun moveBy(dx: Float, dy: Float) {
        val state = _state.value
        if (!state.anchorEditing) {
            place { it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy) }
            return
        }
        if (session == null || state.working || state.canvasWidth <= 0) return
        val selected = state.selected?.takeIf { it.status == ShotStatus.Ready } ?: return
        if (selected.key == HERO_KEY) {
            val anchor = state.heroAnchor ?: return
            _state.value = state.copy(
                heroAnchor = NormPoint((anchor.x + dx).coerceIn(0f, 1f), (anchor.y + dy).coerceIn(0f, 1f)),
            )
        } else {
            val shot = shotOf(selected) ?: return
            val anchor = selected.anchor ?: return
            val moved = MultiShotLayout.anchorMovedBy(shot, anchor, dx, dy, state.canvasWidth, state.canvasHeight)
            updateItem(selected.key) { it.copy(anchor = moved) }
        }
        publish()
    }

    fun setAnchorEditing(editing: Boolean) {
        val state = _state.value
        if (session == null || state.working || !state.timeline) return
        _state.value = state.copy(anchorEditing = editing)
    }

    /**
     * Free layout: back to the initial contain fit. Time layout (§4.2 requirement 22): back to this
     * photo's slot on the current path at its initial size. Opacity is the user's and stays.
     */
    fun resetPlacement() {
        val state = _state.value
        val key = state.selectedKey
        if (!state.canArrange || key == null) {
            place { ShotPlacement(opacity = it.opacity) }
            return
        }
        val slotted = slotted(state) { shot -> shot.copy(placement = ShotPlacement(opacity = shot.placement.opacity)) }
        val placement = slotted.firstOrNull { it.id == key }?.placement ?: return
        updateItem(key) { it.copy(placement = placement) }
        publish()
    }

    /**
     * §2.1 requirement 4, §4.2: **이 순서로 위치 배치** — confirms the order shown and moves every
     * earlier moment's anchor onto its slot for that order, with the time profile's opacity.
     * Deterministic and from scratch every time, so it never accumulates; size, rotation, masks and
     * refs are kept. Pressed again it is 위치 다시 배치.
     */
    fun placeByOrder() {
        val state = _state.value
        if (session == null || !state.canPlace) return
        layOut(state.copy(orderConfirmed = true))
    }

    /**
     * Lays [confirmed]'s order out — positions and the time profile's opacity — and shows the
     * layout step, so its spacing, strength and hand corrections are at hand.
     */
    private fun layOut(confirmed: MultiShotState) {
        val positioned = slotted(confirmed) { it }
        val faded = MultiShotLayout.faded(positioned, confirmed.layout.strength).associateBy { it.id }
        _state.value = confirmed.copy(
            items = confirmed.items.map { item ->
                faded[item.key]?.let { item.copy(placement = it.placement) } ?: item
            },
            laidOutOrder = confirmed.timeOrder,
            keptPositions = false,
            panel = TimelinePanel.Layout,
            anchorEditing = false,
        )
        publish()
        showSelected()
    }

    /**
     * §2.1 requirement 6: **현재 위치 유지** — the other explicit answer. The order shown is confirmed
     * and the positions and opacity stay exactly as they are.
     */
    fun keepPositions() {
        val state = _state.value
        if (session == null || !state.canPlace) return
        _state.value = state.copy(orderConfirmed = true, laidOutOrder = state.timeOrder, keptPositions = true)
        publish()
    }

    /**
     * §2.1 requirement 2: the layout step is reachable whichever tile is selected. Showing it ends
     * an open selection, since the canvas shows the composite there.
     */
    fun setPanel(panel: TimelinePanel) {
        val current = session ?: return
        val state = _state.value
        if (state.busy || !state.timeline || state.panel == panel) return
        if (panel == TimelinePanel.Layout) closeSegmentation(current)
        _state.value = _state.value.copy(panel = panel, anchorEditing = false)
        publish()
        showSelected()
    }

    /** §4.2 requirement 21: the direction moves the afterimages only; opacity, size and angle stay. */
    fun setDirection(degrees: Float) = relayout { it.copy(directionDeg = degrees.coerceIn(SHOT_ROTATION_RANGE)) }

    /** §4.2 requirement 21: so does the distance. */
    fun setDistance(distance: Float) = relayout { it.copy(distance = distance.coerceIn(TIMELINE_DISTANCE_RANGE)) }

    /** §4.3: the even arrangement's `spacing × W / N`, around the hero; positions only. */
    fun setSpacing(spacing: Float) = relayout { it.copy(spacing = spacing.coerceIn(0f, 1f)) }

    /**
     * §4.3: 장수별 균등 배치 or 기존 경로 배치. Like direction and distance it moves positions only,
     * and only once the order is confirmed.
     */
    fun setArrangement(arrangement: TimelineArrangement) = relayout { it.copy(arrangement = arrangement) }

    /** §4.2 requirement 19: the strength scales the default profile and changes opacity only. */
    fun setStrength(strength: Float) {
        val state = _state.value
        if (session == null || state.working || !state.timeline) return
        val layout = state.layout.copy(strength = strength.coerceIn(0f, 1f))
        if (!state.canArrange) {
            // §4.2 requirement 11: without a confirmed order a setting is only chosen.
            _state.value = state.copy(layout = layout)
            publish()
            return
        }
        val inTime = state.timeItems.mapNotNull(::shotOf)
        val faded = MultiShotLayout.faded(inTime, layout.strength).associateBy { it.id }
        _state.value = state.copy(
            layout = layout,
            items = state.items.map { item ->
                val opacity = faded[item.key]?.placement?.opacity ?: return@map item
                item.copy(placement = item.placement.copy(opacity = opacity))
            },
        )
        publish()
    }

    private fun relayout(change: (TimelineLayout) -> TimelineLayout) {
        val state = _state.value
        if (session == null || state.working || !state.timeline) return
        val updated = state.copy(layout = change(state.layout))
        if (!updated.canArrange) {
            // §4.2 requirement 11: chosen now, used by 이 순서로 위치 배치; the slots follow it.
            _state.value = updated
            publish()
            return
        }
        val positioned = slotted(updated) { it }.associateBy { it.id }
        _state.value = updated.copy(
            items = updated.items.map { item ->
                positioned[item.key]?.let { item.copy(placement = it.placement) } ?: item
            },
            laidOutOrder = updated.timeOrder,
            keptPositions = false,
        )
        publish()
    }

    /** The placed shots in time order, each first passed through [before], then put on its slot. */
    private fun slotted(state: MultiShotState, before: (Shot) -> Shot): List<Shot> {
        val hero = state.heroAnchor ?: return emptyList()
        val inTime = state.timeItems.mapNotNull(::shotOf).map(before)
        return MultiShotLayout.positioned(inTime, hero, state.layout, state.canvasWidth, state.canvasHeight)
    }

    /** §2 requirement 8: the free layout's preset — opacity only; order, place, size and masks stay. */
    fun applyAfterimage() {
        val state = _state.value
        if (session == null || state.working || state.items.isEmpty() || state.timeline) return
        _state.value = state.copy(
            items = state.items.mapIndexed { index, item ->
                item.copy(placement = item.placement.copy(opacity = afterimage(index, state.items.size)))
            },
        )
        publish()
    }

    private fun place(change: (ShotPlacement) -> ShotPlacement) {
        val state = _state.value
        val key = state.selectedKey ?: return
        if (session == null || key == HERO_KEY || state.selected?.status != ShotStatus.Ready) return
        updateItem(key) { it.copy(placement = change(it.placement).coerced()) }
        publish()
    }

    // ---- commit ------------------------------------------------------------------------------

    /** §2 requirement 9: one history entry — a new composite, an edited one, or its removal. */
    fun apply() {
        val current = session ?: return
        val state = _state.value
        if (!state.canApply || host.currentDocument() != current.entry) return
        val shots = state.draftShots
        val timeline = draftTimeline(current, state)
        val mode = state.mode ?: MultiShotMode.Free
        val committed = current.entry.withMultiShot(shots, current.draftId, mode, timeline)
        val kept = (shots.map { it.subjectRef } + listOfNotNull(timeline?.hero?.ref)).toSet()
        current.owned.entries.removeAll { it.value in kept }
        close()
        host.commit(committed)
    }

    /**
     * §3: the timeline that goes into the document — every shot in time order, the layout settings
     * and the hero's mask. Whether the order is confirmed is its own flag (REVIEW R2): a photo
     * changed in the free layout asks for the order again, it never costs the hero or the settings.
     * Null only when there is nothing of the time layout to keep — every composite saved before it.
     */
    private fun draftTimeline(current: Session, state: MultiShotState): Timeline? {
        val heroEntry = current.entries[HERO_KEY]
        val heroRef = heroEntry?.subjectRef
        val anchor = state.heroAnchor
        // A hero being selected again keeps its stored mask until the new one is written.
        val hero = if (heroRef != null && anchor != null) {
            HeroMask(heroRef, heroEntry.widthPx, heroEntry.heightPx, anchor)
        } else {
            null
        }
        if (!state.orderConfirmed && hero == null && state.layout == TimelineLayout()) return null
        val placed = state.draftShots.map { it.id }.toSet()
        return Timeline(
            order = state.timeOrder.filter { it in placed },
            layout = state.layout,
            hero = hero,
            orderConfirmed = state.orderConfirmed,
        )
    }

    fun showMessage(@StringRes res: Int) {
        _state.value = _state.value.copy(message = res)
    }

    fun onMessageShown() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun startWork(current: Session, @StringRes label: Int, restore: () -> Unit, block: suspend () -> Unit) {
        current.job?.cancel()
        val work = ++current.work
        current.restore = restore
        _state.value = _state.value.copy(working = true, workLabel = label, message = null)
        current.job = scope.launch {
            try {
                block()
            } finally {
                // Only the job that owns the flag may clear it.
                if (session === current && current.work == work) {
                    current.restore = null
                    _state.value = _state.value.copy(working = false, workLabel = null)
                }
            }
        }
    }

    private fun inPhoto(x: Float, y: Float): Boolean = x in 0f..1f && y in 0f..1f

    private fun isCurrent(current: Session, key: String, token: Int): Boolean =
        session === current && current.entries[key]?.request == token &&
            host.currentDocument() == current.entry

    /**
     * The draft shots, rebuilt from the items: only placed subjects are drawn. Also what follows
     * from them on the canvas — whether a subject is cut by its edge, and the selected anchor.
     */
    private fun publish() {
        if (session == null) return
        val state = _state.value
        val shots = state.items.mapNotNull { item -> shotOf(item)?.takeIf { item.status == ShotStatus.Ready } }
        val sized = state.canvasWidth > 0 && state.canvasHeight > 0
        val clipped = state.timeline && sized && state.items.any { item ->
            val shot = shotOf(item)
            val bounds = item.bounds
            item.status == ShotStatus.Ready && shot != null && bounds != null &&
                MultiShotLayout.isClipped(shot, bounds, state.canvasWidth, state.canvasHeight)
        }
        val selected = state.selected?.takeIf { it.status == ShotStatus.Ready && state.timeline && sized }
        val marker = when {
            selected == null -> null
            selected.key == HERO_KEY -> state.heroAnchor?.let { CanvasPoint(it.x, it.y) }
            else -> selected.anchor?.let { anchor ->
                shotOf(selected)?.let { MultiShotLayout.onCanvas(it, anchor, state.canvasWidth, state.canvasHeight) }
            }
        }
        val (start, slots) = slotMarkers(state).takeIf { state.layoutShown } ?: (null to emptyList())
        val even = state.layout.arrangement == TimelineArrangement.Even
        _state.value = state.copy(
            draftShots = shots,
            clipped = clipped,
            anchorMarker = marker.takeUnless { state.layoutShown },
            // §4.3: the even arrangement shows the common baseline instead of a path.
            pathStart = start.takeUnless { even },
            slots = slots,
        )
    }

    /**
     * §4.2 requirement 12: the path's start and one numbered target per earlier moment, in the order
     * shown. A slot is drawn filled only while its photo's anchor actually sits on it, so a photo
     * moved by hand is not shown as laid out.
     */
    private fun slotMarkers(state: MultiShotState): Pair<CanvasPoint?, List<SlotMarker>>? {
        val hero = state.heroAnchor ?: return null
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0 || state.timeItems.isEmpty()) return null
        val count = state.timeItems.size
        val slots = state.timeItems.mapIndexed { index, item ->
            val target = MultiShotLayout.target(hero, state.layout, index, count, state.canvasWidth, state.canvasHeight)
            val anchor = item.anchor
            val shot = shotOf(item)
            val at = if (anchor != null && shot != null) {
                MultiShotLayout.onCanvas(shot, anchor, state.canvasWidth, state.canvasHeight)
            } else {
                null
            }
            val occupied = at != null && abs(at.x - target.x) < SLOT_TOLERANCE && abs(at.y - target.y) < SLOT_TOLERANCE
            SlotMarker(target, index + 1, occupied)
        }
        return slots.first().point to slots
    }

    /** The item as a shot, whatever its status, once its subject file exists. */
    private fun shotOf(item: ShotItem): Shot? {
        val entry = session?.entries?.get(item.key) ?: return null
        val ref = entry.subjectRef ?: return null
        if (item.key == HERO_KEY) return null
        return Shot(item.key, ref, entry.widthPx, entry.heightPx, item.placement, item.anchor)
    }

    /** What the canvas shows for the selected photo: itself until it is placed (§2). */
    private fun showSelected() {
        val current = session ?: return
        val state = _state.value
        val entry = state.selectedKey?.let { current.entries[it] }
        val photo = entry?.photo?.preview?.takeUnless { state.arranging }
        _state.value = state.copy(
            photo = photo?.asImageBitmap(),
            mask = if (current.segKey == state.selectedKey) state.mask else null,
        )
    }

    private fun setMask(current: Session, mask: Bitmap?) {
        if (session !== current) return
        _state.value = _state.value.copy(mask = mask)
    }

    /** Off the main thread: a merge walks every pixel of the photo preview. */
    private suspend fun merge(base: Bitmap?, incoming: Bitmap): Bitmap =
        if (base == null) {
            incoming
        } else {
            withContext(dispatchers.default) { MaskOps.merged(base, incoming, MergeMode.Add) }
        }

    private fun updateItem(key: String, change: (ShotItem) -> ShotItem) {
        val state = _state.value
        _state.value = if (key == HERO_KEY) {
            state.copy(hero = state.hero?.let(change))
        } else {
            state.copy(items = state.items.map { if (it.key == key) change(it) else it })
        }
    }

    /**
     * REVIEW R1: a failed or cancelled import puts the item back exactly — a new photo goes, a
     * replaced one returns with its ref, transform and place in both orders — and the draft is
     * rebuilt from it, so the preview and 적용 see what the thumbnails show.
     */
    private fun restoreItem(key: String, before: ShotItem?) {
        val state = _state.value
        _state.value = if (before == null) {
            session?.entries?.remove(key)
            val items = state.items.filterNot { it.key == key }
            state.copy(items = items, selectedKey = items.lastOrNull()?.key, timeOrder = state.timeOrder - key)
        } else {
            state.copy(items = state.items.map { if (it.key == key) before else it })
        }
        publish()
        showSelected()
    }

    private fun revertToPicked(key: String) {
        session?.let(::closeSegmentation)
        updateItem(key) { it.copy(status = ShotStatus.Picked) }
        showSelected()
    }

    private fun failExtraction(key: String, error: AppError) {
        updateItem(key) { it.copy(status = ShotStatus.Picked, problem = extractionMessage(error)) }
        showSelected()
    }

    /**
     * Ends the open SAM 3 session. REVIEW R2: the photo it was open on — whichever one — goes back
     * to a retryable photo, so no item is ever left Selecting without a session.
     */
    private fun closeSegmentation(current: Session) {
        val seg = current.seg
        val key = current.segKey
        current.seg = null
        current.segKey = null
        current.baseMask = null
        current.candidates = emptyList()
        _state.value = _state.value.copy(mask = null, candidateCount = 0, chosenCandidate = null)
        if (key != null) {
            updateItem(key) { if (it.status == ShotStatus.Selecting) it.copy(status = ShotStatus.Picked) else it }
        }
        if (seg != null) scope.launch(NonCancellable) { segmentation.close(seg) }
    }

    private fun onSettingsChanged() {
        val current = session ?: return
        // §8: nothing more goes to the new server without a new press.
        current.run?.let { endRun(current, it) }
        val state = _state.value
        val key = current.segKey
            ?: (state.items + listOfNotNull(state.hero)).firstOrNull { it.status == ShotStatus.Extracting }?.key
            ?: return
        if (state.selected?.status == ShotStatus.Saving) return
        cancelWork()
        current.entries[key]?.let { it.request++ }
        closeSegmentation(current)
        updateItem(key) { it.copy(status = ShotStatus.Picked, problem = R.string.multishot_settings_changed) }
        showSelected()
    }

    private fun scaled(bitmap: Bitmap, longEdgePx: Int): Bitmap {
        val scale = longEdgePx.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).roundToInt()),
            maxOf(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }

    companion object {
        /** The size the photo is shown and uploaded at, like the selection tool's preview. */
        const val PHOTO_PREVIEW_PX = 1080

        /** A slot counts as occupied within half a percent of the canvas. */
        const val SLOT_TOLERANCE = 0.005f
        const val THUMBNAIL_PX = 96
        /**
         * §2: the concept sent for the default extraction. The UI stays Korean; SAM 3 answered
         * nothing for "사람" on the device samples and found the person for "person" (work/RESULT.md).
         */
        const val PERSON = "person"
        const val AFTERIMAGE_SINGLE = 0.5f
        val AFTERIMAGE_PAIR = listOf(0.35f, 0.65f)

        /** The free preset for [count] shots: 50% alone, else 35% → 65% evenly (two: 35 / 65). */
        fun afterimage(index: Int, count: Int): Float =
            if (count <= 1) {
                AFTERIMAGE_SINGLE
            } else {
                AFTERIMAGE_PAIR.first() + (AFTERIMAGE_PAIR.last() - AFTERIMAGE_PAIR.first()) * index / (count - 1)
            }

        @StringRes
        fun importMessage(error: AppError): Int = when (error) {
            AppError.Unsupported -> R.string.multishot_import_unsupported
            AppError.TooLarge -> R.string.multishot_import_too_large
            else -> R.string.multishot_import_failed
        }

        @StringRes
        fun extractionMessage(error: AppError): Int = when (error) {
            is AppError.Invalid, AppError.Unauthorized -> R.string.multishot_server_settings
            AppError.Unavailable, is AppError.Io -> R.string.multishot_server_unreachable
            else -> R.string.multishot_extract_failed
        }

        fun serverOf(availability: Availability): MultiShotServer = when (availability) {
            Availability.Ready -> MultiShotServer.Ready
            is Availability.Unavailable -> when (availability.reason) {
                is AppError.Invalid, AppError.Unauthorized -> MultiShotServer.NeedsSettings
                else -> MultiShotServer.Unreachable
            }
        }
    }
}
