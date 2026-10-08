package com.diffuse.feature.editor.tools.multishot

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import com.diffuse.core.imaging.model.MAX_SHOTS
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.TimelineLayout
import com.diffuse.core.imaging.render.CanvasPoint
import com.diffuse.feature.editor.R

/**
 * specs/multishot.md §2. Where one added photograph stands. Only [Ready] has a subject on disk;
 * every other status is a photograph the user still has to finish or delete before 적용.
 */
enum class ShotStatus {
    /** The picked photograph is being read. */
    Importing,

    /** Read and on the canvas; nothing has been sent anywhere. */
    Picked,

    /** 피사체 추출 is running: the upload and the first "사람" query. */
    Extracting,

    /** A SAM 3 session is open on this photograph and the selection can be refined. */
    Selecting,

    /** The subject is being cut out and written to the project folder. */
    Saving,

    /** The subject PNG exists; the shot can be placed. */
    Ready,

    /** One of several picked photographs would not read; only 교체 or 삭제 resolve it. */
    Unreadable,
}

/**
 * One thumbnail in the sheet. [problem] is the photo's own error line, kept until it is fixed.
 * [anchor] is where the time layout holds the subject (§4.2) and [bounds] the subject's extent in
 * the photo, both as fractions of the photo; the bounds are only kept in memory, for the clip check.
 */
data class ShotItem(
    val key: String,
    val status: ShotStatus,
    val thumbnail: ImageBitmap? = null,
    val placement: ShotPlacement = ShotPlacement(),
    @StringRes val problem: Int? = null,
    val anchor: NormPoint? = null,
    val bounds: RectF? = null,
)

/**
 * What one Photo Picker launch is for: new photographs — at most [max] — or a replacement for
 * [replaceKey]. [id] ties the answer to the session that asked, so a late one is dropped.
 */
data class PickRequest(val id: String, val replaceKey: String?, val max: Int)

/** The step of 모두 추출하고 자동 배치 the current photograph is in. */
enum class RunPhase {
    /** The current photo is still being rendered as the hero's input. */
    Preparing,

    /** Uploaded and asked for "person". */
    Extracting,

    /** The subject is being cut out and written. */
    Saving,

    /** None or several people were found: the run waits for the user's choice on this photo. */
    Choosing,
}

/** One run's progress: the [step]th of [total] photographs it extracts, and what it is doing. */
data class RunProgress(val step: Int, val total: Int, val phase: RunPhase)

/** specs/multishot.md §2.1: the time layout shows one of these under the timeline. */
enum class TimelinePanel {
    /** The selected tile's own step: its extraction, or its subject's size, angle and opacity. */
    Photos,

    /** The common placement step: direction, distance, strength, and placing in the shown order. */
    Layout,
}

/** §4.2 requirement 12: one target slot on the path, numbered earliest first. */
data class SlotMarker(val point: CanvasPoint, val number: Int, val occupied: Boolean)

/**
 * §2.1 requirement 5: what still stands between the user and placing — the first one only, so
 * the sheet can name it. [step] is the 1-based time step of the photo concerned, if any.
 */
data class PlaceBlocker(@StringRes val message: Int, val step: Int? = null)

/** specs/multishot.md §8: what the last SAM 3 availability said, for the sheet's server line. */
enum class MultiShotServer { Ready, NeedsSettings, Unreachable }

/**
 * specs/multishot.md §2. Sheet state; nothing here is in the document until 적용.
 *
 * [photo] and [mask] are what the canvas shows while the selected photograph is not yet a placed
 * subject — the photo itself, not the composite (§2 "선택 단계"). For the last moment ([hero]) the
 * photo is the composite's input canvas.
 *
 * [items] is the free layout's drawing order; [timeOrder] is the time layout's order, earliest
 * first, by the same keys. They are separate data: one never rewrites the other (§2.1).
 */
data class MultiShotState(
    val open: Boolean = false,
    /** Null until a new composite's layout is chosen; a stored composite opens in its own. */
    val mode: MultiShotMode? = null,
    val items: List<ShotItem> = emptyList(),
    val selectedKey: String? = null,
    val photo: ImageBitmap? = null,
    val mask: Bitmap? = null,
    val candidateCount: Int = 0,
    val chosenCandidate: Int? = null,
    val phrase: String = "",
    val working: Boolean = false,
    @StringRes val workLabel: Int? = null,
    val server: MultiShotServer = MultiShotServer.Ready,
    val serverHost: String = "",
    /** The entry document already had a composite, so removing every shot is a change. */
    val hadComposite: Boolean = false,
    /** The shots the draft renders: the [ShotStatus.Ready] items, in drawing order. */
    val draftShots: List<Shot> = emptyList(),
    val pickRequest: PickRequest? = null,
    @StringRes val message: Int? = null,
    // ---- the time layout (§2.1, §4.2, §6) ----
    val timeOrder: List<String> = emptyList(),
    /** Only an order the user confirmed is laid out; import order is not capture order. */
    val orderConfirmed: Boolean = false,
    /**
     * The order the current positions were settled for — laid out by 이 순서로 위치 배치 or kept by
     * 현재 위치 유지 ([keptPositions]); null when neither happened. Not the confirmation itself.
     */
    val laidOutOrder: List<String>? = null,
    val keptPositions: Boolean = false,
    val panel: TimelinePanel = TimelinePanel.Photos,
    /** §4.2 requirement 12: the path's start and the numbered slots, while the layout step is shown. */
    val pathStart: CanvasPoint? = null,
    val slots: List<SlotMarker> = emptyList(),
    /** The last moment, fixed at the end of the timeline: [HERO_KEY], never in [items]. */
    val hero: ShotItem? = null,
    val heroAnchor: NormPoint? = null,
    /** The composite's canvas, at the size its hero is selected on; only its shape matters. */
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
    val layout: TimelineLayout = TimelineLayout(),
    /**
     * 모두 추출하고 자동 배치 is under way — running, or waiting for a choice on the selected photo.
     * Null when no run is; then nothing is extracted or laid out on its own.
     */
    val run: RunProgress? = null,
    /** A drag moves the selected anchor instead of the subject. */
    val anchorEditing: Boolean = false,
    /** Some subject reaches past the canvas and will be cut there (requirement 18). */
    val clipped: Boolean = false,
    /** The selected item's anchor on the canvas, for the overlay's marker. */
    val anchorMarker: CanvasPoint? = null,
) {

    val selected: ShotItem?
        get() = if (selectedKey == HERO_KEY) hero else items.firstOrNull { it.key == selectedKey }

    val timeline: Boolean get() = mode == MultiShotMode.Timeline

    /** The added photos, earliest first. */
    val timeItems: List<ShotItem>
        get() = timeOrder.mapNotNull { key -> items.firstOrNull { it.key == key } }

    /** §2: the canvas shows the composite for a placed subject and for the layout step. */
    val arranging: Boolean
        get() = (timeline && panel == TimelinePanel.Layout) || selected?.status == ShotStatus.Ready || selected == null

    val layoutShown: Boolean get() = timeline && panel == TimelinePanel.Layout

    /** §4.3: total moments, the current photo included. */
    val totalMoments: Int get() = items.size + 1

    /** §4.3: the even arrangement shows a baseline through the hero's anchor, not a path. */
    val showsBaseline: Boolean
        get() = layoutShown && layout.arrangement == TimelineArrangement.Even && heroAnchor != null

    /** Working, or a run waiting for a choice: the photos and their order stay as they are. */
    val busy: Boolean get() = working || run != null

    val canAddPhoto: Boolean get() = !busy && mode != null && items.size < MAX_ITEMS

    /** Something still has to be extracted before the time layout can be laid out. */
    val pending: Boolean
        get() = hero?.status != ShotStatus.Ready || heroAnchor == null || items.any { it.status != ShotStatus.Ready }

    /**
     * 모두 추출하고 자동 배치: every photo has been read, and a run that would send anything has a
     * server it may send to. With nothing pending it only lays the shown order out, locally.
     */
    val canRun: Boolean
        get() = timeline && !busy && items.isNotEmpty() &&
            items.none { it.status == ShotStatus.Importing || it.status == ShotStatus.Unreadable } &&
            (!pending || server != MultiShotServer.NeedsSettings)

    val canExtract: Boolean
        get() = !busy && server != MultiShotServer.NeedsSettings &&
            selected?.status.let { it == ShotStatus.Picked || it == ShotStatus.Selecting }

    /** A selection exists and no candidate batch is waiting for a choice. */
    val canFinishExtraction: Boolean
        get() = !working && selected?.status == ShotStatus.Selecting && mask != null &&
            (candidateCount == 0 || chosenCandidate != null)

    /** §2.1 requirement 5: the first thing missing before the shown order can be placed. */
    val placeBlocker: PlaceBlocker?
        get() = when {
            items.isEmpty() -> PlaceBlocker(R.string.multishot_needs_photos)
            hero?.status != ShotStatus.Ready || heroAnchor == null -> PlaceBlocker(R.string.multishot_needs_hero)
            else -> timeItems.indexOfFirst { it.status != ShotStatus.Ready || it.anchor == null }
                .takeIf { it >= 0 }
                ?.let { PlaceBlocker(R.string.multishot_needs_extraction, it + 1) }
                ?: PlaceBlocker(R.string.multishot_needs_canvas).takeIf { canvasWidth <= 0 || canvasHeight <= 0 }
        }

    /**
     * §2.1 requirement 4: everything is extracted, so 이 순서로 위치 배치 / 현재 위치 유지 may run —
     * they are what confirm the order shown.
     */
    val canPlace: Boolean get() = timeline && !busy && placeBlocker == null

    /** §4.2: relayout by direction and distance needs an order the user already confirmed. */
    val canArrange: Boolean get() = canPlace && orderConfirmed

    /** §2.1 requirement 16: the positions were settled — laid out or kept — for the order shown. */
    val positionsCurrent: Boolean get() = orderConfirmed && laidOutOrder == timeOrder

    /** §2.1 requirement 7: the order shown is not what the positions were settled for. */
    val layoutStale: Boolean get() = canPlace && !positionsCurrent

    /**
     * §2 requirement 9: no half-finished photograph is ever left out silently, and a new composite
     * needs something visible. Removing every shot of an existing composite is a change on its own.
     * §6 requirement 13: the time layout also needs its hero and a confirmed order.
     */
    val canApply: Boolean
        get() = !working && items.all { it.status == ShotStatus.Ready } &&
            when {
                items.isEmpty() -> hadComposite
                !items.any { it.placement.opacity > 0f } -> false
                // §2.1 requirement 6: never finished with the positions left unsettled.
                timeline -> canArrange && positionsCurrent
                else -> mode != null
            }

    /** The status of the photo — or, for [HERO_KEY], the hero — under [key]. */
    fun statusOf(key: String): ShotStatus? =
        if (key == HERO_KEY) hero?.status else items.firstOrNull { it.key == key }?.status

    /** Nothing is left to extract for [key]: its subject is saved (the hero's with its anchor). */
    fun isDone(key: String): Boolean = when (key) {
        HERO_KEY -> hero?.status == ShotStatus.Ready && heroAnchor != null
        else -> items.firstOrNull { it.key == key }?.let { it.status == ShotStatus.Ready } ?: true
    }

    companion object {
        const val MAX_ITEMS = MAX_SHOTS
        const val HERO_KEY = "hero"
    }
}
