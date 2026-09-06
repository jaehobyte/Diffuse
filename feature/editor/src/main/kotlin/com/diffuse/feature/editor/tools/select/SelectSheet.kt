package com.diffuse.feature.editor.tools.select

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val SelectSheetTestTag = "SelectSheet"
const val SelectInvertTestTag = "SelectInvert"
const val SelectClearTestTag = "SelectClear"
const val SelectHintTestTag = "SelectHint"

/** DESIGN.md §4: pills, so the secondary actions match every other button in the app. */
private val ActionHeight = 40.dp
private val ActionRadius = 16.dp

/**
 * specs/selection_tool.md §1. Title, the two selection actions, a hint line, and the pinned
 * [취소 | 적용] row `EditSheet` already owns.
 */
@Composable
fun SelectSheet(
    state: SelectionState,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.select_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.hasMask,
        modifier = modifier.testTag(SelectSheetTestTag),
    ) {
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
        // specs/selection_tool.md §7: hints are text under the buttons, never a snackbar.
        Text(
            text = stringResource(
                if (state.lowConfidence) R.string.select_low_confidence else R.string.select_hint,
            ),
            style = Typography.bodySm,
            color = colors.inkSecondary,
            modifier = Modifier.testTag(SelectHintTestTag),
        )
    }
}

@Composable
private fun SecondaryAction(
    testTag: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Text(
        text = label,
        style = Typography.bodyStrong,
        color = colors.ink.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        modifier = Modifier
            .testTag(testTag)
            .height(ActionHeight)
            .border(1.dp, colors.hairline, RoundedCornerShape(ActionRadius))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** DESIGN.md §4: disabled is 38% alpha with the colour unchanged. */
private const val DISABLED_ALPHA = 0.38f
