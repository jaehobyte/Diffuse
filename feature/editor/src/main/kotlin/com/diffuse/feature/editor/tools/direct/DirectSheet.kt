package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.labelRes
import kotlin.math.roundToInt

const val DirectSheetTestTag = "DirectSheet"
const val DirectStepsTestTag = "DirectSteps"
const val DirectHintTestTag = "DirectHint"

/** The −100…100 integer the sliders already display, so a step reads in the same units (§11). */
private const val PERCENT_SCALE = 100

/**
 * specs/vibe_edit.md §3. Title, the prompt bar, the step list once a plan arrives, and the
 * pinned [취소 | 적용] row `EditSheet` owns — 적용 is the sheet's one accent.
 */
@Composable
fun DirectSheet(
    state: DirectState,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** specs/prompt_input.md §2: the sheet hosts the bar, it never replaces the buttons. */
    promptBar: (@Composable () -> Unit)? = null,
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
        state.plan?.let { plan ->
            Column(
                modifier = Modifier.testTag(DirectStepsTestTag),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                plan.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. ${stepLine(step)}",
                        style = Typography.bodyMd,
                        color = colors.ink,
                    )
                }
            }
        }
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
 * specs/vibe_edit.md §3, §11: every line is built here, from a template and the adjustment
 * labels the manual sheets use. Nothing the model wrote reaches the screen.
 */
@Composable
private fun stepLine(step: PlanStep): String = when (step) {
    is PlanStep.Select -> stringResource(R.string.direct_step_select, step.phrase)
    is PlanStep.Adjust -> stringResource(
        if (step.masked) R.string.direct_step_adjust_masked else R.string.direct_step_adjust,
        stringResource(step.kind.labelRes()),
        (step.value * PERCENT_SCALE).roundToInt(),
    )
    PlanStep.Erase -> stringResource(R.string.direct_step_erase)
    PlanStep.CutOut -> stringResource(R.string.direct_step_cutout)
}
