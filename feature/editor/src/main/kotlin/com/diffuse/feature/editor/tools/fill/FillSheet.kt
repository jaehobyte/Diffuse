package com.diffuse.feature.editor.tools.fill

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.feature.editor.R

const val FillSheetTestTag = "FillSheet"

/**
 * specs/generative_fill.md §6. Title, the prompt bar that supplies the noun, and the pinned
 * [취소 | 적용] row `EditSheet` owns — 적용 is the sheet's one accent, so the send icon stays
 * `editInk` and every sheet commits the same way (DESIGN.md §4).
 */
@Composable
fun FillSheet(
    state: FillState,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    /** specs/prompt_input.md §2: the sheet hosts the bar, it never replaces the buttons. */
    promptBar: (@Composable () -> Unit)? = null,
) {
    EditSheet(
        title = stringResource(R.string.fill_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(FillSheetTestTag),
    ) {
        promptBar?.invoke()
    }
}
