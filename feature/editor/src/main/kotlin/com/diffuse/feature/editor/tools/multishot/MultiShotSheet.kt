package com.diffuse.feature.editor.tools.multishot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.SHOT_ROTATION_RANGE
import com.diffuse.core.imaging.model.SHOT_SCALE_RANGE
import com.diffuse.core.imaging.model.TIMELINE_DISTANCE_RANGE
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.PromptBar
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

const val MultiShotSheetTestTag = "MultiShotSheet"
const val MultiShotAddTestTag = "MultiShotAdd"
const val MultiShotExtractTestTag = "MultiShotExtract"
const val MultiShotFinishTestTag = "MultiShotFinish"
const val MultiShotAfterimageTestTag = "MultiShotAfterimage"
const val MultiShotStatusTestTag = "MultiShotStatus"
const val MultiShotOpacityTestTag = "MultiShotOpacity"
const val MultiShotScaleTestTag = "MultiShotScale"
const val MultiShotRotationTestTag = "MultiShotRotation"
const val MultiShotFreeModeTestTag = "MultiShotFreeMode"
const val MultiShotTimelineModeTestTag = "MultiShotTimelineMode"
const val MultiShotRunTestTag = "MultiShotRun"
const val MultiShotCancelRunTestTag = "MultiShotCancelRun"
const val MultiShotProgressTestTag = "MultiShotProgress"
const val MultiShotReverseTestTag = "MultiShotReverse"
const val MultiShotPlaceTestTag = "MultiShotPlace"
const val MultiShotKeepTestTag = "MultiShotKeep"
const val MultiShotPhotosPanelTestTag = "MultiShotPhotosPanel"
const val MultiShotLayoutPanelTestTag = "MultiShotLayoutPanel"
const val MultiShotBlockerTestTag = "MultiShotBlocker"
const val MultiShotEvenTestTag = "MultiShotEven"
const val MultiShotPathTestTag = "MultiShotPath"
const val MultiShotSpacingTestTag = "MultiShotSpacing"
const val MultiShotTotalTestTag = "MultiShotTotal"
const val MultiShotDistanceTestTag = "MultiShotDistance"
const val MultiShotStrengthTestTag = "MultiShotStrength"
const val MultiShotHeroTestTag = "MultiShotHero"
const val MultiShotEarlierTestTag = "MultiShotEarlier"
const val MultiShotLaterTestTag = "MultiShotLater"
const val MultiShotAnchorTestTag = "MultiShotAnchor"
const val MultiShotClippedTestTag = "MultiShotClipped"

fun multiShotThumbTag(index: Int): String = "MultiShotThumb:$index"

fun multiShotCandidateTag(index: Int): String = "MultiShotCandidate:$index"

fun multiShotDirectionTag(degrees: Float): String = "MultiShotDirection:${degrees.roundToInt()}"

/**
 * §4.2: the directions offered, named by where the oldest moment starts and the hero ends. The
 * first is a new proposal's: oldest on the left, the hero on the right.
 */
private val DIRECTIONS = listOf(
    0f to R.string.multishot_direction_right,
    180f to R.string.multishot_direction_left,
    45f to R.string.multishot_direction_down_right,
    135f to R.string.multishot_direction_down_left,
    -45f to R.string.multishot_direction_up_right,
    -135f to R.string.multishot_direction_up_left,
)

private const val PERCENT = 100f

/** The sheet's callbacks, bundled so the composable stays under detekt's parameter ceiling. */
@Suppress("LongParameterList") // A plain bundle of the sheet's actions.
class MultiShotActions(
    val onAdd: () -> Unit,
    val onReplace: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (String, Boolean) -> Unit,
    val onExtract: () -> Unit,
    val onChooseCandidate: (Int) -> Unit,
    val onPhraseChange: (String) -> Unit,
    val onSubmitPhrase: () -> Unit,
    val onFinishExtraction: () -> Unit,
    val onOpacityChange: (Float) -> Unit,
    val onScaleChange: (Float) -> Unit,
    val onRotationChange: (Float) -> Unit,
    val onResetPlacement: () -> Unit,
    val onAfterimage: () -> Unit,
    val onRecheckServer: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCancel: () -> Unit,
    val onApply: () -> Unit,
    // §2.1, §4.2: the time layout's actions.
    val onModeChange: (MultiShotMode) -> Unit = {},
    val onMoveInTime: (String, Boolean) -> Unit = { _, _ -> },
    val onReverseTime: () -> Unit = {},
    val onRun: () -> Unit = {},
    val onCancelRun: () -> Unit = {},
    val onPlace: () -> Unit = {},
    val onKeepPositions: () -> Unit = {},
    val onPanelChange: (TimelinePanel) -> Unit = {},
    val onArrangementChange: (TimelineArrangement) -> Unit = {},
    val onSpacingChange: (Float) -> Unit = {},
    val onDirectionChange: (Float) -> Unit = {},
    val onDistanceChange: (Float) -> Unit = {},
    val onStrengthChange: (Float) -> Unit = {},
    val onAnchorEditingChange: (Boolean) -> Unit = {},
    val onReselectHero: () -> Unit = {},
)

/**
 * specs/multishot.md §2. The added photographs as thumbnails, then **only the selected one's**
 * controls — its extraction while it is a photo, its placement once it is a subject — so the
 * sheet stays inside `EditSheet`'s 45% and the canvas keeps the rest. A new composite first
 * chooses its layout; the time layout (§2.1) shows its steps in turn — order, extraction, layout.
 */
@Composable
fun MultiShotSheet(
    state: MultiShotState,
    actions: MultiShotActions,
    modifier: Modifier = Modifier,
) {
    EditSheet(
        title = stringResource(R.string.multishot_title),
        onCancel = actions.onCancel,
        onApply = actions.onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(MultiShotSheetTestTag),
    ) {
        when {
            state.mode == null -> ModeChoice(state, actions)
            state.timeline -> {
                ModeRow(state, actions)
                TimelineContent(state, actions)
            }
            else -> {
                ModeRow(state, actions)
                FreeContent(state, actions)
            }
        }
    }
}

@Composable
private fun FreeContent(state: MultiShotState, actions: MultiShotActions) {
    if (state.items.isEmpty()) {
        EmptyRow(state, actions)
    } else {
        ThumbnailRow(state, actions)
        val selected = state.selected
        if (selected != null) {
            OrderRow(state, selected, actions)
            selected.problem?.let { StatusLine(it) }
            if (selected.status == ShotStatus.Ready) {
                PlacementRows(state, selected, actions)
            } else {
                ExtractionRows(state, selected, actions)
            }
        }
    }
}

/** §2.1 requirement 1: a new composite says which layout it is before anything is added. */
@Composable
private fun ModeChoice(state: MultiShotState, actions: MultiShotActions) {
    val colors = LocalAppColors.current
    Text(text = stringResource(R.string.multishot_mode_question), style = Typography.bodyMd, color = colors.ink)
    Hint(R.string.multishot_mode_timeline_hint)
    Hint(R.string.multishot_mode_free_hint)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TertiaryPill(
            text = stringResource(R.string.multishot_mode_timeline),
            onClick = { actions.onModeChange(MultiShotMode.Timeline) },
            enabled = !state.working,
            modifier = Modifier.testTag(MultiShotTimelineModeTestTag),
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_mode_free),
            onClick = { actions.onModeChange(MultiShotMode.Free) },
            enabled = !state.working,
            modifier = Modifier.testTag(MultiShotFreeModeTestTag),
        )
    }
}

/** §2.1 requirement 6: switching is explicit and keeps every shot, transform and the order. */
@Composable
private fun ModeRow(state: MultiShotState, actions: MultiShotActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(MultiShotMode.Timeline, R.string.multishot_mode_timeline, MultiShotTimelineModeTestTag),
            Triple(MultiShotMode.Free, R.string.multishot_mode_free, MultiShotFreeModeTestTag),
        ).forEach { (mode, label, tag) ->
            val chosen = state.mode == mode
            TertiaryPill(
                text = choiceText(stringResource(label), chosen),
                onClick = { actions.onModeChange(mode) },
                enabled = !state.busy,
                modifier = Modifier.testTag(tag).semantics { this.selected = chosen },
            )
        }
    }
}

@Composable
private fun choiceText(label: String, chosen: Boolean): String =
    if (chosen) stringResource(R.string.multishot_chosen, label) else label

/**
 * §2.1: the current photo is the last moment, fixed at the end; the added photos come before it,
 * in the order shown. Under the timeline the one main action, whichever tile is selected — 모두
 * 추출하고 자동 배치 — and then one step at a time: the selected tile's own (사진별 편집) or the
 * common placement (배치 위치).
 */
@Composable
private fun TimelineContent(state: MultiShotState, actions: MultiShotActions) {
    val colors = LocalAppColors.current
    Text(text = stringResource(R.string.multishot_timeline_guide), style = Typography.bodyMd, color = colors.ink)
    TimelineRow(state, actions)
    if (state.items.isNotEmpty()) {
        RunRows(state, actions)
        PanelRow(state, actions)
    }
    if (state.clipped) {
        Text(
            text = stringResource(R.string.multishot_clipped),
            style = Typography.bodySm,
            color = colors.ink,
            modifier = Modifier.testTag(MultiShotClippedTestTag),
        )
    }
    val selected = state.selected
    when {
        state.layoutShown -> LayoutRows(state, actions)
        selected == null -> Unit
        selected.key == MultiShotState.HERO_KEY -> HeroRows(state, selected, actions)
        else -> {
            TimeOrderRow(state, selected, actions)
            selected.problem?.let { StatusLine(it) }
            if (selected.status == ShotStatus.Ready) {
                PlacementRows(state, selected, actions)
            } else {
                ExtractionRows(state, selected, actions)
            }
        }
    }
}

/** §2.1 requirement 2: the two steps under the timeline; the layout one never needs the hero tile. */
@Composable
private fun PanelRow(state: MultiShotState, actions: MultiShotActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(TimelinePanel.Photos, R.string.multishot_panel_photos, MultiShotPhotosPanelTestTag),
            Triple(TimelinePanel.Layout, R.string.multishot_panel_layout, MultiShotLayoutPanelTestTag),
        ).forEach { (panel, label, tag) ->
            val chosen = state.panel == panel
            TertiaryPill(
                text = choiceText(stringResource(label), chosen),
                onClick = { actions.onPanelChange(panel) },
                enabled = !state.busy,
                modifier = Modifier.testTag(tag).semantics { this.selected = chosen },
            )
        }
    }
}

/**
 * §2.1: the run's progress, or what stands in its way or changed since the last layout; then the
 * main action — its press confirms the order shown and sends what is still unextracted — and
 * 순서 뒤집기 to fix the order before it.
 */
@Composable
private fun RunRows(state: MultiShotState, actions: MultiShotActions) {
    val run = state.run
    val unreadable = state.timeItems.indexOfFirst { it.status == ShotStatus.Unreadable }
    when {
        run != null -> Text(
            text = stringResource(
                R.string.multishot_run_progress,
                run.step,
                run.total,
                stringResource(phaseText(run.phase)),
            ),
            style = Typography.bodyMd,
            color = LocalAppColors.current.ink,
            modifier = Modifier.testTag(MultiShotProgressTestTag),
        )
        unreadable >= 0 -> Text(
            text = stringResource(R.string.multishot_unreadable_step, unreadable + 1),
            style = Typography.bodyMd,
            color = LocalAppColors.current.ink,
            modifier = Modifier.testTag(MultiShotStatusTestTag),
        )
        else -> {
            if (state.laidOutOrder != null && !state.positionsCurrent) StatusLine(R.string.multishot_layout_stale)
            if (state.pending) {
                Text(
                    text = stringResource(R.string.multishot_run_notice, state.serverHost.ifBlank { "-" }),
                    style = Typography.bodySm,
                    color = LocalAppColors.current.inkSecondary,
                )
            }
        }
    }
    if (state.pending && run == null) ServerRow(state, actions)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (run != null) {
            TertiaryPill(
                text = stringResource(R.string.multishot_run_cancel),
                onClick = actions.onCancelRun,
                modifier = Modifier.testTag(MultiShotCancelRunTestTag),
            )
        } else {
            TertiaryPill(
                text = stringResource(runLabel(state)),
                onClick = actions.onRun,
                enabled = state.canRun,
                modifier = Modifier.testTag(MultiShotRunTestTag),
            )
        }
        TertiaryPill(
            text = stringResource(R.string.multishot_reverse_order),
            onClick = actions.onReverseTime,
            enabled = !state.busy && state.items.size > 1,
            modifier = Modifier.testTag(MultiShotReverseTestTag),
        )
    }
}

/** §2.1: the same action, named by what it still has to do. */
private fun runLabel(state: MultiShotState): Int = when {
    !state.pending && state.positionsCurrent && !state.keptPositions -> R.string.multishot_run_again
    !state.pending -> R.string.multishot_run_place
    state.hero?.status == ShotStatus.Ready || state.items.any { it.status == ShotStatus.Ready } ->
        R.string.multishot_run_rest
    else -> R.string.multishot_run_all
}

private fun phaseText(phase: RunPhase): Int = when (phase) {
    RunPhase.Preparing -> R.string.multishot_preparing
    RunPhase.Extracting -> R.string.multishot_extracting
    RunPhase.Saving -> R.string.multishot_saving
    RunPhase.Choosing -> R.string.multishot_run_choosing
}

/**
 * §2.1 requirements 2–6, §4.2: the common placement step — what is still missing, the direction
 * (named start → end), distance and strength, what each overwrites, and the two explicit answers:
 * 이 순서로 위치 배치 (위치 다시 배치 once settled) or 현재 위치 유지.
 */
@Composable
private fun LayoutRows(state: MultiShotState, actions: MultiShotActions) {
    state.placeBlocker?.let { blocker ->
        val text = if (blocker.step == null) {
            stringResource(blocker.message)
        } else {
            stringResource(blocker.message, blocker.step)
        }
        Text(
            text = text,
            style = Typography.bodyMd,
            color = LocalAppColors.current.ink,
            modifier = Modifier.testTag(MultiShotBlockerTestTag),
        )
    }
    Text(
        text = stringResource(R.string.multishot_total, state.totalMoments, MultiShotState.MAX_ITEMS + 1),
        style = Typography.bodySm,
        color = LocalAppColors.current.inkSecondary,
        modifier = Modifier.testTag(MultiShotTotalTestTag),
    )
    ArrangementRow(state, actions)
    val even = state.layout.arrangement == TimelineArrangement.Even
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val settled = state.positionsCurrent && !state.keptPositions
        TertiaryPill(
            text = when {
                settled -> stringResource(R.string.multishot_place_again)
                even -> stringResource(R.string.multishot_place_even, state.totalMoments)
                else -> stringResource(R.string.multishot_place)
            },
            onClick = actions.onPlace,
            enabled = state.canPlace,
            modifier = Modifier.testTag(MultiShotPlaceTestTag),
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_keep_positions),
            onClick = actions.onKeepPositions,
            enabled = state.canPlace && !state.positionsCurrent,
            modifier = Modifier.testTag(MultiShotKeepTestTag),
        )
    }
    if (even) EvenRows(state, actions) else PathRows(state, actions)
    LabeledSlider(
        label = stringResource(R.string.multishot_strength),
        value = state.layout.strength * PERCENT,
        range = 0f..PERCENT,
        format = { "${it.roundToInt()}%" },
        onChange = { actions.onStrengthChange(it / PERCENT) },
        tag = MultiShotStrengthTestTag,
    )
    Hint(R.string.multishot_layout_overwrites)
}

/** §2.1 requirement 3: earliest first, the step under each thumbnail, the hero last. */
@Composable
private fun TimelineRow(state: MultiShotState, actions: MultiShotActions) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        state.timeItems.forEachIndexed { index, item ->
            val step = stringResource(R.string.multishot_step, index + 1)
            StepColumn {
                Thumb(state, item, multiShotThumbTag(index), step, "${index + 1}", actions)
                StepLabel(step)
            }
        }
        state.hero?.let { hero ->
            val label = stringResource(R.string.multishot_hero)
            StepColumn {
                Thumb(state, hero, MultiShotHeroTestTag, label, stringResource(R.string.multishot_hero_short), actions)
                StepLabel(label)
            }
        }
        if (state.items.size < MultiShotState.MAX_ITEMS) {
            TertiaryPill(
                text = stringResource(R.string.multishot_add),
                onClick = actions.onAdd,
                enabled = state.canAddPhoto,
                modifier = Modifier.testTag(MultiShotAddTestTag),
            )
        }
    }
}

@Composable
private fun StepLabel(text: String) {
    Text(text = text, style = Typography.label, color = LocalAppColors.current.inkSecondary)
}

/** §2.1 requirement 3: earlier / later in time; replace and remove as in the free layout. */
@Composable
private fun TimeOrderRow(state: MultiShotState, selected: ShotItem, actions: MultiShotActions) {
    val index = state.timeOrder.indexOf(selected.key)
    val idle = !state.busy
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TertiaryPill(
            text = stringResource(R.string.multishot_earlier),
            onClick = { actions.onMoveInTime(selected.key, true) },
            enabled = idle && index > 0,
            modifier = Modifier.testTag(MultiShotEarlierTestTag),
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_later),
            onClick = { actions.onMoveInTime(selected.key, false) },
            enabled = idle && index in 0 until state.timeOrder.lastIndex,
            modifier = Modifier.testTag(MultiShotLaterTestTag),
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_replace),
            onClick = { actions.onReplace(selected.key) },
            enabled = idle,
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_remove),
            onClick = { actions.onRemove(selected.key) },
            enabled = idle,
        )
    }
}

/**
 * §6: the last moment — selected on the composite's own input until it has a mask. Once it has one
 * it stays where it is; only its anchor (the path's end) can be corrected, or the mask redone.
 */
@Composable
private fun HeroRows(state: MultiShotState, hero: ShotItem, actions: MultiShotActions) {
    hero.problem?.let { StatusLine(it) }
    if (hero.status != ShotStatus.Ready) {
        // §2.1: the run extracts the hero; the user is asked only when it needs a choice.
        if (hero.status == ShotStatus.Selecting || hero.status == ShotStatus.Saving) {
            Hint(R.string.multishot_hero_guide)
            ExtractionRows(state, hero, actions)
        }
        return
    }
    Hint(R.string.multishot_hero_ready)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnchorPill(state, actions)
        TertiaryPill(
            text = stringResource(R.string.multishot_reselect_hero),
            onClick = actions.onReselectHero,
            enabled = !state.busy,
        )
    }
}

/** §4.3: 장수별 균등 배치 (a new proposal's) or the earlier path; the stored one opens as it was. */
@Composable
private fun ArrangementRow(state: MultiShotState, actions: MultiShotActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(TimelineArrangement.Even, R.string.multishot_arrangement_even, MultiShotEvenTestTag),
            Triple(TimelineArrangement.Path, R.string.multishot_arrangement_path, MultiShotPathTestTag),
        ).forEach { (arrangement, label, tag) ->
            val chosen = state.layout.arrangement == arrangement
            TertiaryPill(
                text = choiceText(stringResource(label), chosen),
                onClick = { actions.onArrangementChange(arrangement) },
                enabled = !state.working,
                modifier = Modifier.testTag(tag).semantics { this.selected = chosen },
            )
        }
    }
}

/**
 * §4.3: the hero stays where it is and the group of N equal columns is centred on it, so an
 * off-centre hero takes the group with it and an even N puts the hero left of the middle — said,
 * not hidden. No direction arrow: the hero is the newest but sits in the middle.
 */
@Composable
private fun EvenRows(state: MultiShotState, actions: MultiShotActions) {
    Hint(R.string.multishot_even_hint)
    if (state.totalMoments % 2 == 0) Hint(R.string.multishot_even_even_count)
    LabeledSlider(
        label = stringResource(R.string.multishot_spacing),
        value = state.layout.spacing * PERCENT,
        range = 0f..PERCENT,
        format = { "${it.roundToInt()}%" },
        onChange = { actions.onSpacingChange(it / PERCENT) },
        tag = MultiShotSpacingTestTag,
    )
    if (state.layout.spacing == 0f) Hint(R.string.multishot_distance_zero)
}

/** §4.2: the earlier path — direction named start → end, and its length. */
@Composable
private fun PathRows(state: MultiShotState, actions: MultiShotActions) {
    DirectionRow(state, actions)
    LabeledSlider(
        label = stringResource(R.string.multishot_distance),
        value = state.layout.distance * PERCENT,
        range = TIMELINE_DISTANCE_RANGE.start * PERCENT..TIMELINE_DISTANCE_RANGE.endInclusive * PERCENT,
        format = { "${it.roundToInt()}%" },
        onChange = { actions.onDistanceChange(it / PERCENT) },
        tag = MultiShotDistanceTestTag,
    )
    if (state.layout.distance == 0f) Hint(R.string.multishot_distance_zero)
}

/** §4.2 requirement 3: where the oldest moment starts and the hero ends; the first is the default. */
@Composable
private fun DirectionRow(state: MultiShotState, actions: MultiShotActions) {
    Text(
        text = stringResource(R.string.multishot_direction),
        style = Typography.bodyMd,
        color = LocalAppColors.current.ink,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DIRECTIONS.forEach { (degrees, label) ->
            val chosen = state.layout.directionDeg == degrees
            TertiaryPill(
                text = choiceText(stringResource(label), chosen),
                onClick = { actions.onDirectionChange(degrees) },
                enabled = !state.working,
                modifier = Modifier.testTag(multiShotDirectionTag(degrees)).semantics { this.selected = chosen },
            )
        }
    }
}

@Composable
private fun Hint(res: Int) {
    Text(text = stringResource(res), style = Typography.bodySm, color = LocalAppColors.current.inkSecondary)
}

@Composable
private fun StepColumn(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** §4.2 requirement 14: while on, a drag corrects the anchor and the subject stays put. */
@Composable
private fun AnchorPill(state: MultiShotState, actions: MultiShotActions) {
    TertiaryPill(
        text = choiceText(stringResource(R.string.multishot_anchor), state.anchorEditing),
        onClick = { actions.onAnchorEditingChange(!state.anchorEditing) },
        enabled = !state.working,
        modifier = Modifier.testTag(MultiShotAnchorTestTag).semantics { this.selected = state.anchorEditing },
    )
}

@Composable
private fun EmptyRow(state: MultiShotState, actions: MultiShotActions) {
    val colors = LocalAppColors.current
    Text(text = stringResource(R.string.multishot_empty), style = Typography.bodyMd, color = colors.ink)
    TertiaryPill(
        text = stringResource(R.string.multishot_add),
        onClick = actions.onAdd,
        enabled = state.canAddPhoto,
        modifier = Modifier.testTag(MultiShotAddTestTag),
    )
}

/** §2 requirement 3: the order here is the drawing order; later is on top. */
@Composable
private fun ThumbnailRow(state: MultiShotState, actions: MultiShotActions) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.items.forEachIndexed { index, item ->
            val label = stringResource(R.string.multishot_photo, index + 1)
            Thumb(state, item, multiShotThumbTag(index), label, "${index + 1}", actions)
        }
        if (state.items.size < MultiShotState.MAX_ITEMS) {
            TertiaryPill(
                text = stringResource(R.string.multishot_add),
                onClick = actions.onAdd,
                enabled = state.canAddPhoto,
                modifier = Modifier.testTag(MultiShotAddTestTag),
            )
        }
    }
}

/** One selectable 48dp thumbnail; [fallback] is drawn until its picture arrives. */
@Suppress("LongParameterList")
@Composable
private fun Thumb(
    state: MultiShotState,
    item: ShotItem,
    tag: String,
    label: String,
    fallback: String,
    actions: MultiShotActions,
) {
    val colors = LocalAppColors.current
    val selected = item.key == state.selectedKey
    val shape = RoundedCornerShape(THUMB_RADIUS)
    val base = Modifier
        .testTag(tag)
        .size(THUMB_SIZE)
        .clip(shape)
        .background(colors.surfaceRaised)
        .border(THUMB_RING, if (selected) colors.ink else Color.Transparent, shape)
        .semantics { this.selected = selected }
        .clickable(enabled = !state.busy, role = Role.RadioButton, onClickLabel = label) {
            actions.onSelect(item.key)
        }
    val thumbnail = item.thumbnail
    if (thumbnail != null) {
        Image(bitmap = thumbnail, contentDescription = label, contentScale = ContentScale.Crop, modifier = base)
    } else {
        Box(modifier = base.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Text(text = fallback, style = Typography.label, color = colors.ink)
        }
    }
}

@Composable
private fun OrderRow(state: MultiShotState, selected: ShotItem, actions: MultiShotActions) {
    val index = state.items.indexOf(selected)
    val idle = !state.working
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TertiaryPill(
            text = stringResource(R.string.multishot_to_back),
            onClick = { actions.onMove(selected.key, false) },
            enabled = idle && index > 0,
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_to_front),
            onClick = { actions.onMove(selected.key, true) },
            enabled = idle && index < state.items.lastIndex,
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_replace),
            onClick = { actions.onReplace(selected.key) },
            enabled = idle,
        )
        TertiaryPill(
            text = stringResource(R.string.multishot_remove),
            onClick = { actions.onRemove(selected.key) },
            enabled = idle,
        )
    }
}

/**
 * §2 requirements 4–6: where the photo goes, and the explicit step that sends it. The time layout
 * sends through its run (§2.1), so there only a choice the run waits for is shown here.
 */
@Composable
private fun ExtractionRows(state: MultiShotState, selected: ShotItem, actions: MultiShotActions) {
    val colors = LocalAppColors.current
    if (state.timeline) {
        if (selected.status == ShotStatus.Selecting || selected.status == ShotStatus.Saving) {
            SelectingRows(state, actions)
        }
        return
    }
    Text(
        text = stringResource(R.string.multishot_upload_notice, state.serverHost.ifBlank { "-" }),
        style = Typography.bodySm,
        color = colors.inkSecondary,
    )
    ServerRow(state, actions)
    when (selected.status) {
        ShotStatus.Picked, ShotStatus.Extracting, ShotStatus.Importing -> TertiaryPill(
            text = stringResource(R.string.multishot_extract),
            onClick = actions.onExtract,
            enabled = state.canExtract,
            modifier = Modifier.testTag(MultiShotExtractTestTag),
        )
        ShotStatus.Selecting, ShotStatus.Saving -> SelectingRows(state, actions)
        ShotStatus.Ready, ShotStatus.Unreadable -> Unit
    }
}

@Composable
private fun ServerRow(state: MultiShotState, actions: MultiShotActions) {
    if (state.server == MultiShotServer.Ready) return
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                if (state.server == MultiShotServer.NeedsSettings) {
                    R.string.multishot_server_settings
                } else {
                    R.string.multishot_server_unreachable
                },
            ),
            style = Typography.bodySm,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        if (state.server == MultiShotServer.Unreachable) {
            TertiaryPill(text = stringResource(R.string.multishot_recheck), onClick = actions.onRecheckServer)
        }
        TertiaryPill(text = stringResource(R.string.multishot_settings), onClick = actions.onOpenSettings)
    }
}

/** §2 requirement 5: pick among several answers, refine by points and by an extra phrase. */
@Composable
private fun SelectingRows(state: MultiShotState, actions: MultiShotActions) {
    val colors = LocalAppColors.current
    Text(text = stringResource(R.string.multishot_refine_hint), style = Typography.bodySm, color = colors.inkSecondary)
    if (state.candidateCount > 0) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(state.candidateCount) { index ->
                val chosen = state.chosenCandidate == index
                TertiaryPill(
                    text = stringResource(
                        if (chosen) R.string.multishot_candidate_chosen else R.string.multishot_candidate,
                        index + 1,
                    ),
                    onClick = { actions.onChooseCandidate(index) },
                    enabled = !state.working,
                    modifier = Modifier.testTag(multiShotCandidateTag(index)).semantics { this.selected = chosen },
                )
            }
        }
    }
    PromptBar(
        value = state.phrase,
        onValueChange = actions.onPhraseChange,
        onSubmit = { actions.onSubmitPhrase() },
        placeholder = stringResource(R.string.multishot_phrase_placeholder),
        enabled = !state.working,
    )
    TertiaryPill(
        text = stringResource(R.string.multishot_finish),
        onClick = actions.onFinishExtraction,
        enabled = state.canFinishExtraction,
        modifier = Modifier.testTag(MultiShotFinishTestTag),
    )
}

/** §2 requirements 7–8: the selected subject only — its opacity, size and angle. */
@Composable
private fun PlacementRows(state: MultiShotState, selected: ShotItem, actions: MultiShotActions) {
    val placement = selected.placement
    // §4.2 requirement 14: a drag moves the subject; 기준점 조정 moves only its anchor.
    if (state.timeline) Hint(if (state.anchorEditing) R.string.multishot_anchor_hint else R.string.multishot_drag_hint)
    LabeledSlider(
        label = stringResource(R.string.multishot_opacity),
        value = placement.opacity * PERCENT,
        range = 0f..PERCENT,
        format = { "${it.roundToInt()}%" },
        onChange = { actions.onOpacityChange(it / PERCENT) },
        tag = MultiShotOpacityTestTag,
    )
    // The size slider moves on a log scale so 1× sits where a double-tap resets it to (0).
    LabeledSlider(
        label = stringResource(R.string.multishot_scale),
        value = ln(placement.scale),
        range = ln(SHOT_SCALE_RANGE.start)..ln(SHOT_SCALE_RANGE.endInclusive),
        format = { "${(exp(it) * PERCENT).roundToInt()}%" },
        onChange = { actions.onScaleChange(exp(it)) },
        tag = MultiShotScaleTestTag,
    )
    LabeledSlider(
        label = stringResource(R.string.multishot_rotation),
        value = placement.rotationDeg,
        range = SHOT_ROTATION_RANGE,
        format = { String.format(Locale.US, "%.0f°", it) },
        onChange = actions.onRotationChange,
        tag = MultiShotRotationTestTag,
        zeroCentered = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TertiaryPill(text = stringResource(R.string.multishot_reset_position), onClick = actions.onResetPlacement)
        // §4.2 requirement 20: the free preset and the time profile are not the same button.
        if (state.timeline) {
            AnchorPill(state, actions)
            TertiaryPill(
                text = stringResource(R.string.multishot_panel_layout),
                onClick = { actions.onPanelChange(TimelinePanel.Layout) },
                enabled = !state.working,
            )
        } else {
            TertiaryPill(
                text = stringResource(R.string.multishot_afterimage),
                onClick = actions.onAfterimage,
                modifier = Modifier.testTag(MultiShotAfterimageTestTag),
            )
        }
    }
}

/** A labelled `AdjustSlider` that also reads its name and value to a screen reader. */
@Suppress("LongParameterList")
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    tag: String,
    zeroCentered: Boolean = false,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = Typography.bodyMd, color = colors.ink)
        AdjustSlider(
            value = value.coerceIn(range),
            range = range,
            zeroCentered = zeroCentered,
            onChange = { onChange(it.coerceIn(range)) },
            onChangeFinished = {},
            format = format,
            modifier = Modifier.testTag(tag).semantics {
                contentDescription = label
                stateDescription = format(value)
                setProgress { target ->
                    onChange(target.coerceIn(range))
                    true
                }
            },
        )
    }
}

@Composable
private fun StatusLine(res: Int) {
    Text(
        text = stringResource(res),
        style = Typography.bodyMd,
        color = LocalAppColors.current.ink,
        modifier = Modifier.testTag(MultiShotStatusTestTag),
    )
}

private val THUMB_SIZE = 48.dp
private val THUMB_RADIUS = 12.dp
private val THUMB_RING = 2.dp
