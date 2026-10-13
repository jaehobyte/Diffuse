package com.diffuse.feature.editor.tools.select

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.OptionChip
import com.diffuse.core.ui.components.SecondaryPill
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val SelectSheetTestTag = "SelectSheet"
const val SelectModeRowTestTag = "SelectModeRow"
const val SelectInvertTestTag = "SelectInvert"
const val SelectClearTestTag = "SelectClear"
const val SelectCutOutTestTag = "SelectCutOut"
const val SelectHintTestTag = "SelectHint"

/**
 * specs/selection_tool.md §1. Title, the two selection actions, a hint line, and the pinned
 * [취소 | 적용] row `EditSheet` already owns.
 */
@Composable
fun SelectSheet(
    state: SelectionState,
    onModeChange: (MergeMode) -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onCutOut: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** specs/prompt_input.md §4: the sheet hosts the prompt bar, below the buttons. */
    promptBar: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.select_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.hasMask,
        modifier = modifier.testTag(SelectSheetTestTag),
    ) {
        Row(
            modifier = Modifier.testTag(SelectModeRowTestTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MergeMode.entries.forEach { entry ->
                ModeChip(
                    mode = entry,
                    selected = entry == state.mode,
                    onClick = { onModeChange(entry) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryAction(
                testTag = SelectInvertTestTag,
                label = stringResource(R.string.select_invert),
                enabled = state.hasMask,
                onClick = onInvert,
            )
            SecondaryAction(
                testTag = SelectClearTestTag,
                label = stringResource(R.string.select_clear),
                enabled = state.hasMask,
                onClick = onClear,
            )
        }
        // specs/selection_tool.md §8.2 calls this a primary pill, but DESIGN.md §1 allows a
        // surface one filled primary and that is the sheet's Apply. A second filled pill would
        // make two different commits look equally primary. Secondary keeps the hierarchy honest.
        if (state.hasMask) {
            SecondaryAction(
                testTag = SelectCutOutTestTag,
                label = stringResource(R.string.select_cutout),
                enabled = true,
                onClick = onCutOut,
            )
        }
        promptBar?.invoke()
        // specs/selection_tool.md §7: hints are text under the buttons, never a snackbar.
        Text(
            text = stringResource(hintFor(state)),
            style = Typography.bodySm,
            color = colors.inkSecondary,
            modifier = Modifier.testTag(SelectHintTestTag),
        )
    }
}

/** specs/selection_tool.md §7: one line, and never blocking. */
private fun hintFor(state: SelectionState) = when {
    state.notFound -> R.string.select_not_found
    state.lowConfidence -> R.string.select_low_confidence
    else -> R.string.select_hint
}

/** DESIGN.md §4: the selected mode is a light-blue option chip, matching the crop presets. */
@Composable
private fun ModeChip(mode: MergeMode, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(mode.labelRes)
    OptionChip(text = label, selected = selected, onClick = onClick, modifier = Modifier.testTag(label))
}

@Composable
private fun SecondaryAction(
    testTag: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SecondaryPill(text = label, onClick = onClick, enabled = enabled, modifier = Modifier.testTag(testTag))
}
