package com.diffuse.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.percentFormat
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

/** Test tag per slider row, so a tool sheet can be driven by the kind it adjusts. */
fun adjustSliderTag(kind: AdjustKind): String = "AdjustSlider:${kind.name}"

/**
 * specs/adjust_color.md: "The sheet composable is the same generic AdjustSheet(kinds); do
 * not duplicate it." Light, Color and Detail differ only in title and kind list.
 *
 * Values are read from [document] on every recomposition, so re-opening the sheet shows
 * what is stored rather than zeros.
 */
@Composable
fun AdjustSheet(
    title: String,
    kinds: List<AdjustKind>,
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = title,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    ) {
        kinds.forEach { kind ->
            Column(
                modifier = Modifier.testTag(adjustSliderTag(kind)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(kind.labelRes()),
                    style = Typography.label,
                    color = colors.inkSecondary,
                )
                AdjustSlider(
                    value = document.adjustValue(kind),
                    range = kind.range,
                    zeroCentered = kind.isZeroCentered(),
                    onChange = { onValueChange(kind, it) },
                    onChangeFinished = onValueChangeFinished,
                    format = percentFormat(kind.isZeroCentered()),
                )
            }
        }
    }
}

/** Zero-centred kinds span negative values; the one-sided kinds start at 0. */
internal fun AdjustKind.isZeroCentered(): Boolean = range.start < 0f
