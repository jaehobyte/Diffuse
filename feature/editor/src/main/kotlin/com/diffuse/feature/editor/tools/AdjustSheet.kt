package com.diffuse.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.diffuse.feature.editor.R

const val MaskedOnlyToggleTestTag = "MaskedOnly"

/** The sliders show the masked value when the toggle is on, so the sheet never lies. */
private fun activeMaskId(document: EditDocument, option: MaskOption): String? =
    if (option.available && option.maskedOnly) document.activeMaskId else null

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
    maskOption: MaskOption = MaskOption.None,
    /**
     * specs/adjust_hsl.md §6: 혼합 puts its band chips here. Empty by default, so the three
     * sheets that came first render exactly as they did.
     */
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = title,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    ) {
        if (maskOption.available) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.adjust_masked_only),
                    style = Typography.bodyMd,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = maskOption.maskedOnly,
                    onCheckedChange = maskOption.onMaskedOnlyChange,
                    modifier = Modifier.testTag(MaskedOnlyToggleTestTag),
                )
            }
        }
        header()
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
                    value = document.adjustValue(kind, activeMaskId(document, maskOption)),
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
