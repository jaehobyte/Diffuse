package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.diffuse.core.ai.ColorNote
import com.diffuse.core.ai.ColorRole
import com.diffuse.core.ai.Confidence
import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.PlanDirection
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanItem
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.TopazBlock
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.HoldPill
import com.diffuse.core.ui.components.SecondaryPill
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.style.styleNameRes
import com.diffuse.feature.editor.tools.crop.preset
import com.diffuse.feature.editor.tools.crop.presetLabelRes
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import com.diffuse.feature.editor.tools.enhance.labelRes
import com.diffuse.feature.editor.tools.enhance.messageFor
import com.diffuse.feature.editor.tools.stepLabel
import kotlin.math.roundToInt

const val DirectSheetTestTag = "DirectSheet"
const val DirectStepsTestTag = "DirectSteps"
const val DirectHintTestTag = "DirectHint"
const val DirectReviewTestTag = "DirectReview"
const val DirectObservationsTestTag = "DirectObservations"
const val DirectGoalsTestTag = "DirectGoals"
const val DirectPreserveTestTag = "DirectPreserve"
const val DirectChecksTestTag = "DirectChecks"
const val DirectOutcomeTestTag = "DirectOutcome"
const val DirectDirectionTestTag = "DirectDirection"
const val DirectConfirmTestTag = "DirectConfirm"
const val DirectCandidateTestTag = "DirectCandidate"
const val DirectCompareTestTag = "DirectCompare"
const val DirectReduceTestTag = "DirectReduce"
const val DirectProgressTestTag = "DirectProgress"
const val DirectChoiceTestTag = "DirectChoice"
const val DirectSaveTestTag = "DirectSave"
const val DirectRunNoteTestTag = "DirectRunNote"

/** §19: one toggle row, by the item's stable id. */
fun directItemTag(id: String) = "DirectItem:$id"

/** DESIGN.md §5: a toggle row is at least one touch target tall. */
private val MIN_TOUCH = 48.dp

/** The −100…100 integer the sliders already display, so a step reads in the same units (§11). */
private const val PERCENT_SCALE = 100

/**
 * specs/vibe_edit.md §3, §14. Title, the prompt bar, the area under it, the review once a
 * proposal arrives, and the pinned [취소 | 적용] row `EditSheet` owns — 적용 is the sheet's one
 * filled primary, enabled only for a ready proposal. `EditSheet` scrolls the content and keeps
 * the row pinned, so a long review never pushes 적용 away.
 */
// Each callback is one of the sheet's own buttons; §18 added the candidate's two.
@Suppress("LongParameterList")
@Composable
fun DirectSheet(
    state: DirectState,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** specs/prompt_input.md §2: the sheet hosts the bar, it never replaces the buttons. */
    promptBar: (@Composable () -> Unit)? = null,
    /** §14: the examples and the 보정 추천 entry, directly under the bar while nothing runs. */
    suggestions: (@Composable () -> Unit)? = null,
    /** §18: held 전/후 on the candidate. */
    onCompare: (Boolean) -> Unit = {},
    /** §18: 강도 줄이기. */
    onReduce: () -> Unit = {},
    /** §19: one item on or off, by its id. */
    onToggle: (String) -> Unit = {},
    /** §19, D098: 고해상도 저장 of the reviewed Topaz result. */
    onSave: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    val candidate = state.candidate
    EditSheet(
        title = stringResource(R.string.direct_title),
        onCancel = onCancel,
        onApply = onApply,
        applyLabel = primaryLabel(state)?.let { stringResource(it) },
        applyEnabled = state.canApply,
        cancelLabel = when (candidate?.phase) {
            CandidatePhase.Confirming -> stringResource(R.string.direct_confirm_stop)
            CandidatePhase.Review, CandidatePhase.Stale -> stringResource(R.string.direct_candidate_discard)
            else -> null
        },
        modifier = modifier.testTag(DirectSheetTestTag),
    ) {
        if (candidate == null) {
            promptBar?.invoke()
            suggestions?.invoke()
        } else {
            CandidateSection(candidate, state, onCompare, onReduce, onSave)
        }
        state.proposal?.let { Review(it, state, onToggle) }
        // §10: the one thing that is a hint rather than a snackbar.
        if (state.notUnderstood) {
            Text(
                text = stringResource(R.string.direct_not_understood),
                style = Typography.bodySm,
                color = colors.inkSecondary,
                modifier = Modifier.testTag(DirectHintTestTag),
            )
        }
    }
}

/**
 * §14: what was seen, what to change and for whom, what to keep, what will run and what to check
 * — each its own section, so an observation never reads as a goal. The lines under the titles
 * are the model's short plain text, set as [Text] and nothing else; the step lines are local.
 */
@Composable
private fun Review(proposal: PlanProposal, state: DirectState, onToggle: (String) -> Unit) {
    val phase = state.phase
    Column(
        modifier = Modifier.testTag(DirectReviewTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        outcomeTitle(phase)?.let { Outcome(it, proposal.reason) }
        proposal.direction?.let { Direction(it) }
        Section(R.string.direct_review_observations, DirectObservationsTestTag, proposal.observations.isNotEmpty()) {
            proposal.observations.forEach { observation ->
                Line(
                    stringResource(
                        R.string.direct_review_observation,
                        observation.subject,
                        observation.note,
                        stringResource(observation.confidence.labelRes()),
                    ),
                )
                observation.unknown?.let { Secondary(stringResource(R.string.direct_review_unknown, it)) }
            }
        }
        val emphasized = proposal.colors.filter { it.role == ColorRole.Emphasize }
        Section(
            R.string.direct_review_goals,
            DirectGoalsTestTag,
            proposal.goals.isNotEmpty() || emphasized.isNotEmpty(),
        ) {
            proposal.goals.forEach { goal -> Line(goalLine(goal)) }
            emphasized.forEach { Line(colorLine(it)) }
        }
        val kept = proposal.colors.filter { it.role != ColorRole.Emphasize }
        Section(
            R.string.direct_review_preserve,
            DirectPreserveTestTag,
            proposal.intent.constraints.isNotEmpty() || kept.isNotEmpty(),
        ) {
            proposal.intent.constraints.forEach { constraint ->
                Line(
                    if (constraint.explicit) {
                        constraint.text
                    } else {
                        stringResource(R.string.direct_review_constraint_inferred, constraint.text)
                    },
                )
            }
            kept.forEach { Line(colorLine(it)) }
        }
        if (phase == ProposalPhase.Ready) Steps(proposal, state, onToggle)
        Section(R.string.direct_review_checks, DirectChecksTestTag, proposal.limitations.isNotEmpty()) {
            proposal.limitations.forEach { Line(it) }
        }
    }
}

/** §14: why nothing will run, and that rephrasing asks again. */
@Composable
private fun Outcome(@StringRes title: Int, reason: String?) {
    Column(
        modifier = Modifier.testTag(DirectOutcomeTestTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = stringResource(title), style = Typography.bodyStrong, color = LocalAppColors.current.ink)
        reason?.let { Line(it) }
        Secondary(stringResource(R.string.direct_outcome_edit_hint))
    }
}

/**
 * §15: the one objective and what it rests on, before any step — short, in the existing review's
 * line style, with a default intention marked as such.
 */
@Composable
private fun Direction(direction: PlanDirection) {
    Section(R.string.direct_review_direction, DirectDirectionTestTag, visible = true) {
        val source = stringResource(
            if (direction.source == GoalSource.User) {
                R.string.direct_review_goal_user
            } else {
                R.string.direct_review_goal_default
            },
        )
        Line(stringResource(R.string.direct_review_direction_objective, source, direction.objective))
        Secondary(stringResource(R.string.direct_review_direction_subject, direction.subject))
        if (direction.strengths.isNotEmpty()) {
            val strengths = direction.strengths.joinToString(", ")
            Secondary(stringResource(R.string.direct_review_direction_strengths, strengths))
        }
        if (direction.preserve.isNotEmpty()) {
            Secondary(stringResource(R.string.direct_review_direction_preserve, direction.preserve.joinToString(", ")))
        }
        Secondary(stringResource(R.string.direct_review_direction_success, direction.success))
    }
}

/**
 * §11, §19: the steps as items the user turns on or off — what each one does and to what, why
 * (the model's expected effect and caution), whether it is generative or paid, and whether it can
 * run. A selection is not a row: it runs when an item that needs it is on. The count says how many
 * are on; with none, 미리보기 and 적용 are off and the sheet says to choose one.
 */
@Composable
private fun Steps(proposal: PlanProposal, state: DirectState, onToggle: (String) -> Unit) {
    val items = state.items
    val phrases = proposal.workflow.mapNotNull { it.step as? PlanStep.Select }.associate { it.label to it.phrase }
    Section(R.string.direct_review_steps, DirectStepsTestTag, visible = true) {
        val on = items.count { it.id in state.chosen }
        Secondary(
            if (on == 0) {
                stringResource(R.string.direct_choice_none)
            } else {
                stringResource(R.string.direct_choice_count, on, items.size)
            },
            Modifier.testTag(DirectChoiceTestTag),
        )
        items.forEach { item ->
            ItemRow(
                item = item,
                proposal = proposal,
                phrases = phrases,
                on = item.id in state.chosen,
                block = state.blocks[item.id],
                addition = state.proposals.mode == PlanMode.Direct &&
                    proposal.goal(item.item.goalId)?.source == GoalSource.Default,
                enabled = state.canToggle,
                onToggle = { onToggle(item.id) },
            )
        }
        if (items.any { it.item.effect != null }) Secondary(stringResource(R.string.direct_review_prediction))
        // §16, §18, §19: what costs money, said before anything runs — and that one 실행 reaches it.
        if (items.any { it.step is PlanStep.Topaz }) Secondary(stringResource(R.string.direct_review_topaz_note))
        if (state.topazStep != null && state.candidate == null) {
            Line(stringResource(R.string.direct_run_topaz_note), Modifier.testTag(DirectRunNoteTestTag))
        }
        if (items.any { it.step is PlanStep.Erase || it.step is PlanStep.Fill || it.step is PlanStep.Expand }) {
            Secondary(stringResource(R.string.direct_review_paid))
        }
    }
}

/**
 * §19: one item. The whole row is the toggle — one touch target of at least 48dp, read as a switch
 * with its label and its on/off state — and the state is also written out, never colour alone.
 */
// One row shows one item's every fact; grouping them would only rename the list.
@Suppress("LongParameterList")
@Composable
private fun ItemRow(
    item: PlanItem,
    proposal: PlanProposal,
    phrases: Map<String, String>,
    on: Boolean,
    block: TopazBlock?,
    addition: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val stateText = stringResource(if (on) R.string.direct_item_on else R.string.direct_item_off)
    val goal = proposal.goal(item.item.goalId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { stateDescription = stateText }
            .testTag(directItemTag(item.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(itemLine(item.step, goal, phrases))
            Secondary(
                stringResource(
                    R.string.direct_item_target,
                    targetName(item.step, goal, phrases) ?: stringResource(R.string.direct_item_whole_photo),
                ),
            )
            item.item.effect?.let { Secondary(stringResource(R.string.direct_review_effect, it)) }
            item.item.caution?.let { Secondary(stringResource(R.string.direct_review_caution, it)) }
            costRes(item.step)?.let { Secondary(stringResource(it)) }
            if (addition) Secondary(stringResource(R.string.direct_item_addition))
            block?.let { Secondary(stringResource(it.labelRes())) }
            Secondary(stateText)
        }
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** §19: what an item costs, said on the item — generated patches and the Topaz run. */
@StringRes
private fun costRes(step: PlanStep): Int? = when {
    step is PlanStep.Erase || step is PlanStep.Fill || step is PlanStep.Expand -> R.string.direct_item_generative
    step is PlanStep.Topaz && step.purpose.generative -> R.string.direct_item_topaz_generative
    step is PlanStep.Topaz -> R.string.direct_item_topaz
    else -> null
}

@StringRes
private fun TopazBlock.labelRes(): Int = when (this) {
    TopazBlock.NoKey -> R.string.direct_item_topaz_no_key
    is TopazBlock.Unavailable -> R.string.direct_item_topaz_unavailable
}

/**
 * §19: what an item acts on, in the user's words — the goal's own Korean target when it names
 * one, else the English phrase the selection was made with. Null is the whole photo.
 */
private fun targetName(step: PlanStep, goal: PlanGoal?, phrases: Map<String, String>): String? {
    val label = when (step) {
        is PlanStep.Adjust -> (step.target as? PlanTarget.New)?.label
        is PlanStep.Erase -> step.target
        is PlanStep.CutOut -> step.target
        is PlanStep.Fill -> step.target
        is PlanStep.Select, is PlanStep.Crop, is PlanStep.Expand, is PlanStep.Style, is PlanStep.Topaz -> null
    }
    val existing = (step as? PlanStep.Adjust)?.target is PlanTarget.Existing
    return when {
        label == null && !existing -> null
        goal?.scope == GoalScope.Subject && goal.target != null -> goal.target
        label != null -> phrases[label] ?: label
        else -> null
    }
}

/**
 * §18, §19: where the candidate is. Running says which step and, for Topaz, its stage; Confirming
 * asks about an existing selection on the overlay; Review offers the held compare of the whole
 * result, 강도 줄이기, what each selection was found for and the local findings, with the
 * direction's own success criterion as what to look for — and, with Topaz, 고해상도 저장.
 */
@Composable
private fun CandidateSection(
    candidate: CandidateState,
    state: DirectState,
    onCompare: (Boolean) -> Unit,
    onReduce: () -> Unit,
    onSave: () -> Unit,
) {
    when (candidate.phase) {
        CandidatePhase.Running -> Secondary(progressLine(candidate), Modifier.testTag(DirectProgressTestTag))
        CandidatePhase.Checking ->
            Secondary(stringResource(R.string.direct_checking), Modifier.testTag(DirectProgressTestTag))
        // §19: only an existing selection whose meaning is unknown waits here; a found one never does.
        CandidatePhase.Confirming -> Section(R.string.direct_confirm_title, DirectConfirmTestTag, visible = true) {
            Line(stringResource(R.string.direct_confirm_existing))
            Secondary(stringResource(R.string.direct_confirm_note))
        }
        // §19: the choice moved on and needs a remote call; nothing runs until 다시 실행.
        CandidatePhase.Stale -> Section(R.string.direct_candidate_title, DirectCandidateTestTag, visible = true) {
            Line(stringResource(R.string.direct_choice_stale))
        }
        CandidatePhase.Review -> ReviewSection(candidate, state, onCompare, onReduce, onSave)
    }
}

/**
 * §18, §19: the review — the held compare of the whole result, 강도 줄이기, what each selection was
 * found for and the local findings, the direction's success criterion and, with Topaz, its state
 * and 고해상도 저장.
 */
@Composable
private fun ReviewSection(
    candidate: CandidateState,
    state: DirectState,
    onCompare: (Boolean) -> Unit,
    onReduce: () -> Unit,
    onSave: () -> Unit,
) = Section(R.string.direct_candidate_title, DirectCandidateTestTag, visible = true) {
    if (state.complete) Secondary(stringResource(R.string.direct_candidate_hint))
    TopazLines(candidate, state)
    if (candidate.resolved.isNotEmpty()) {
        Secondary(stringResource(R.string.direct_candidate_resolved, candidate.resolved.joinToString(", ")))
    }
    state.proposal?.direction?.let { Line(stringResource(R.string.direct_review_direction_success, it.success)) }
    candidate.findings.forEach { Line(stringResource(it.labelRes())) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // §19: only the whole chosen result is a 후; a pending Topaz step has nothing to compare.
        if (state.complete) {
            HoldPill(
                text = stringResource(R.string.direct_candidate_compare),
                held = candidate.comparing,
                shown = stringResource(if (candidate.comparing) R.string.topaz_before else R.string.topaz_after),
                onHold = onCompare,
                modifier = Modifier.testTag(DirectCompareTestTag),
            )
        }
        if (state.complete && state.topazStep != null) {
            SecondaryPill(
                text = stringResource(if (candidate.saved) R.string.topaz_save_again else R.string.topaz_save),
                onClick = onSave,
                enabled = candidate.topazPhase == null,
                modifier = Modifier.testTag(DirectSaveTestTag),
            )
        }
        if (candidate.reducible && candidate.topazPhase == null) {
            SecondaryPill(
                text = stringResource(R.string.direct_candidate_reduce),
                onClick = onReduce,
                modifier = Modifier.testTag(DirectReduceTestTag),
            )
        }
    }
}

/** §19: the step in flight — a local step by number, the Topaz step by its stage and real percentage. */
@Composable
private fun progressLine(candidate: CandidateState): String {
    val phase = candidate.topazPhase ?: return stringResource(
        R.string.direct_preview_progress,
        candidate.step + 1,
        candidate.steps,
    )
    val stage = candidate.topazProgress?.takeIf { phase == TopazPhase.Processing }
        ?.let { stringResource(R.string.topaz_progress, it) }
        ?: stringResource(phase.labelRes)
    return stringResource(R.string.direct_topaz_progress, stage, candidate.step + 1, candidate.steps)
}

/**
 * §19: the Topaz step's state for this candidate — its result and what keeps it, why it failed with
 * the way on, or that it has to run again. Nothing for a choice without Topaz.
 */
@Composable
private fun TopazLines(candidate: CandidateState, state: DirectState) {
    if (state.topazStep == null) return
    val result = candidate.topaz
    when {
        result != null -> {
            Line(stringResource(R.string.direct_topaz_done))
            Secondary(stringResource(R.string.topaz_result_size, result.width, result.height))
            Secondary(stringResource(R.string.topaz_adopt_note))
        }
        candidate.topazFailure != null -> {
            Line(stringResource(messageFor(candidate.topazFailure)))
            Secondary(stringResource(R.string.direct_topaz_failed_keep))
        }
        else -> Line(stringResource(R.string.direct_topaz_pending))
    }
}

/**
 * The primary's words: null is `EditSheet`'s own 적용. §19: one 선택한 보정 실행 runs everything
 * chosen; a result, with or without Topaz, is kept by 적용 (고해상도 저장 is the review's
 * secondary), and a candidate whose remote part must run again says 선택한 보정 다시 실행.
 */
@StringRes
private fun primaryLabel(state: DirectState): Int? = when (state.candidate?.phase) {
    CandidatePhase.Confirming -> R.string.direct_confirm_yes
    CandidatePhase.Review -> if (state.topazStep == null || state.candidate.topaz != null) {
        null
    } else {
        R.string.direct_run_again
    }
    CandidatePhase.Running, CandidatePhase.Checking, null -> R.string.direct_run
    CandidatePhase.Stale -> R.string.direct_run_again
}

@StringRes
private fun CandidateFinding.labelRes(): Int = when (this) {
    CandidateFinding.ProtectedChanged -> R.string.direct_finding_protected
    CandidateFinding.FrameCropped -> R.string.direct_finding_cropped
    CandidateFinding.TonalUnderColorRule -> R.string.direct_finding_tonal
    CandidateFinding.Generated -> R.string.direct_finding_generated
}

@Composable
private fun Section(@StringRes title: Int, tag: String, visible: Boolean, content: @Composable () -> Unit) {
    if (!visible) return
    Column(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(title),
            style = Typography.bodyStrong,
            color = LocalAppColors.current.ink,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = Typography.bodyMd, color = LocalAppColors.current.ink, modifier = modifier)
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = Typography.bodySm, color = LocalAppColors.current.inkSecondary, modifier = modifier)
}

@Composable
private fun goalLine(goal: PlanGoal): String {
    val source = stringResource(
        if (goal.source == GoalSource.User) R.string.direct_review_goal_user else R.string.direct_review_goal_default,
    )
    val target = goal.target
    return if (target == null) {
        stringResource(R.string.direct_review_goal, source, goal.text)
    } else {
        stringResource(R.string.direct_review_goal_target, source, goal.text, target)
    }
}

@Composable
private fun colorLine(note: ColorNote): String = stringResource(
    when (note.role) {
        ColorRole.Preserve -> R.string.direct_review_color_preserve
        ColorRole.Emphasize -> R.string.direct_review_color_emphasize
        ColorRole.Background -> R.string.direct_review_color_background
    },
    note.color,
    note.target,
)

@StringRes
private fun outcomeTitle(phase: ProposalPhase): Int? = when (phase) {
    ProposalPhase.NoChange -> R.string.direct_outcome_no_change
    ProposalPhase.NeedsClarification -> R.string.direct_outcome_needs_clarification
    ProposalPhase.Unsupported -> R.string.direct_outcome_unsupported
    else -> null
}

@StringRes
private fun Confidence.labelRes(): Int = when (this) {
    Confidence.High -> R.string.direct_review_confidence_high
    Confidence.Medium -> R.string.direct_review_confidence_medium
    Confidence.Low -> R.string.direct_review_confidence_low
}

/**
 * specs/vibe_edit.md §3, §11, §15: every step line is built here, from a template and the
 * adjustment labels the manual sheets use. The value shown is the final, clamped setting that
 * will run, and a local step says which selection it acts on — a new one by its phrase, an
 * existing one as such, since updating it in place and adding a new one are different edits.
 */
@Composable
private fun itemLine(step: PlanStep, goal: PlanGoal?, phrases: Map<String, String>): String {
    val target = targetName(step, goal, phrases)
    return when (step) {
        is PlanStep.Select -> stringResource(R.string.direct_step_select_new, step.phrase)
        is PlanStep.Adjust -> adjustLine(step, target)
        // §19: "표지판 지우기", the correction itself, rather than the tool's category.
        is PlanStep.Erase -> target?.let { stringResource(R.string.direct_item_erase, it) }
            ?: stringResource(R.string.direct_step_erase)
        is PlanStep.CutOut -> stringResource(R.string.direct_step_cutout)
        // specs/generative_fill.md §8: the prompt is the model's, and English — the same open
        // question `direct_step_select` already carries, rendered through a Korean template.
        is PlanStep.Fill -> stringResource(R.string.direct_step_fill, step.prompt)
        // §4.1: the ratio reads in the same characters the 자르기 sheet is about to show.
        is PlanStep.Crop -> stringResource(
            R.string.direct_step_crop,
            stringResource(presetLabelRes(step.ratio.preset)),
        )
        // outpaint.md in a plan: the same chip characters, the other way round.
        is PlanStep.Expand -> stringResource(
            R.string.direct_step_expand,
            stringResource(presetLabelRes(step.ratio.preset)),
        )
        // specs/style_match.md §6: the preset's own Korean, the same string the 스타일 tile shows.
        is PlanStep.Style -> stringResource(
            R.string.direct_step_style,
            stringResource(styleNameRes(step.style.id)),
        ) + " ${step.intensity}"
        // §16: the purpose's own label from the 화질 개선 sheet, and the options it will open with.
        is PlanStep.Topaz -> topazLine(step)
    }
}

@Composable
private fun topazLine(step: PlanStep.Topaz): String =
    stringResource(R.string.direct_step_topaz, stringResource(step.purpose.labelRes())) + " · " +
        stringResource(
            R.string.direct_step_topaz_options,
            step.options.scale,
            stringResource(
                if (step.options.faceRecovery) {
                    R.string.direct_step_topaz_face_on
                } else {
                    R.string.direct_step_topaz_face_off
                },
            ),
        )

@Composable
private fun adjustLine(step: PlanStep.Adjust, name: String?): String {
    val label = step.kind.stepLabel()
    val value = (step.value * PERCENT_SCALE).roundToInt()
    return when (val target = step.target) {
        null -> stringResource(R.string.direct_step_adjust, label, value)
        is PlanTarget.Existing -> stringResource(R.string.direct_step_adjust_existing, label, value)
        is PlanTarget.New -> stringResource(R.string.direct_step_adjust_new, name ?: target.label, label, value)
    }
}
