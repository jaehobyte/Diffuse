package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.diffuse.core.ai.ColorNote
import com.diffuse.core.ai.ColorRole
import com.diffuse.core.ai.Confidence
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.style.styleNameRes
import com.diffuse.feature.editor.tools.crop.preset
import com.diffuse.feature.editor.tools.crop.presetLabelRes
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

/** The −100…100 integer the sliders already display, so a step reads in the same units (§11). */
private const val PERCENT_SCALE = 100

/**
 * specs/vibe_edit.md §3, §14. Title, the prompt bar, the area under it, the review once a
 * proposal arrives, and the pinned [취소 | 적용] row `EditSheet` owns — 적용 is the sheet's one
 * filled primary, enabled only for a ready proposal. `EditSheet` scrolls the content and keeps
 * the row pinned, so a long review never pushes 적용 away.
 */
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
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.direct_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(DirectSheetTestTag),
    ) {
        promptBar?.invoke()
        if (!state.running) suggestions?.invoke()
        state.proposal?.let { Review(it, state.phase) }
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
private fun Review(proposal: PlanProposal, phase: ProposalPhase) {
    Column(
        modifier = Modifier.testTag(DirectReviewTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        outcomeTitle(phase)?.let { Outcome(it, proposal.reason) }
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
        if (phase == ProposalPhase.Ready) Steps(proposal)
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

/** §11's local step lines, each with the model's expected effect and caution under it. */
@Composable
private fun Steps(proposal: PlanProposal) {
    Section(R.string.direct_review_steps, DirectStepsTestTag, visible = true) {
        proposal.workflow.forEachIndexed { index, item ->
            Line("${index + 1}. ${stepLine(item.step)}")
            item.effect?.let { Secondary(stringResource(R.string.direct_review_effect, it)) }
            item.caution?.let { Secondary(stringResource(R.string.direct_review_caution, it)) }
        }
        if (proposal.workflow.any { it.effect != null }) Secondary(stringResource(R.string.direct_review_prediction))
    }
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
private fun Line(text: String) {
    Text(text = text, style = Typography.bodyMd, color = LocalAppColors.current.ink)
}

@Composable
private fun Secondary(text: String) {
    Text(text = text, style = Typography.bodySm, color = LocalAppColors.current.inkSecondary)
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
 * specs/vibe_edit.md §3, §11: every step line is built here, from a template and the adjustment
 * labels the manual sheets use. The value shown is the final, clamped setting that will run.
 */
@Composable
private fun stepLine(step: PlanStep): String = when (step) {
    is PlanStep.Select -> stringResource(R.string.direct_step_select, step.phrase)
    is PlanStep.Adjust -> stringResource(
        if (step.masked) R.string.direct_step_adjust_masked else R.string.direct_step_adjust,
        step.kind.stepLabel(),
        (step.value * PERCENT_SCALE).roundToInt(),
    )
    PlanStep.Erase -> stringResource(R.string.direct_step_erase)
    PlanStep.CutOut -> stringResource(R.string.direct_step_cutout)
    // specs/generative_fill.md §8: the prompt is the model's, and English — the same open
    // question `direct_step_select` already carries, rendered through a Korean template.
    is PlanStep.Fill -> stringResource(R.string.direct_step_fill, step.prompt)
    // §4.1: the ratio reads in the same characters the 자르기 sheet is about to show.
    is PlanStep.Crop -> stringResource(
        R.string.direct_step_crop,
        stringResource(presetLabelRes(step.ratio.preset)),
    )
    // specs/style_match.md §6: the preset's own Korean, the same string the 스타일 tile shows.
    is PlanStep.Style -> stringResource(
        R.string.direct_step_style,
        stringResource(styleNameRes(step.style.id)),
    )
}
