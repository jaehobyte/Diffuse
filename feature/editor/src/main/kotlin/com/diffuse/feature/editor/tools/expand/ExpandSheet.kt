package com.diffuse.feature.editor.tools.expand

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val ExpandSheetTestTag = "ExpandSheet"
const val ExpandRatioTestTag = "ExpandRatio"

/**
 * specs/outpaint.md §6. Title, the ratio readout, and the pinned [취소 | 적용] row `EditSheet`
 * owns — 적용 is the sheet's one accent.
 *
 * **No prompt bar.** 확대 continues a scene the model can already see; there is nothing for a
 * person to name. That is 채우기's job (generative_fill.md §10).
 *
 * @param sourceAspect the bare source's width ÷ height, which the margins are fractions of.
 */
@Composable
fun ExpandSheet(
    state: ExpandState,
    sourceAspect: Float,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.expand_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(ExpandSheetTestTag),
    ) {
        Text(
            // DESIGN.md §3: `mono` is the role for a computed number, not for prose.
            text = stringResource(
                R.string.expand_ratio,
                ratioText(sourceAspect),
                ratioText(expandedAspect(sourceAspect, 1f, state.margins)),
            ),
            style = Typography.mono,
            color = colors.inkSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.testTag(ExpandRatioTestTag).fillMaxWidth(),
        )
    }
}
