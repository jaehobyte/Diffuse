package com.diffuse.feature.export

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.components.OptionChip
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

private val RowSpacing = 8.dp
private val SectionSpacing = 4.dp

const val ExportSheetTestTag = "ExportSheet"

fun chipTag(label: String): String = "ExportChip:$label"

/** specs/export.md §Sheet: format, size, preset, then [취소 | 저장]. */
@Composable
fun ExportSheet(
    settings: ExportSettings,
    onSettingsChange: (ExportSettings) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditSheet(
        title = stringResource(R.string.export_title),
        onCancel = onCancel,
        onApply = onSave,
        applyLabel = stringResource(R.string.export_save),
        modifier = modifier.testTag(ExportSheetTestTag),
    ) {
        ChipSection(
            labelRes = R.string.export_format,
            options = ExportFormat.entries,
            selected = settings.format,
            labelOf = { it.labelRes },
            onSelect = { onSettingsChange(settings.copy(format = it)) },
        )
        ChipSection(
            labelRes = R.string.export_size,
            options = ExportSize.entries,
            selected = settings.size,
            labelOf = { it.labelRes },
            onSelect = { onSettingsChange(settings.copy(size = it)) },
        )
        ChipSection(
            labelRes = R.string.export_preset,
            options = ExportPreset.entries,
            selected = settings.preset,
            labelOf = { it.labelRes },
            onSelect = { onSettingsChange(settings.copy(preset = it)) },
        )
    }
}

@Composable
private fun <T> ChipSection(
    labelRes: Int,
    options: List<T>,
    selected: T,
    labelOf: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
        Text(
            text = stringResource(labelRes),
            style = Typography.label,
            color = colors.inkSecondary,
        )
        // The 48dp chips and a larger font scale can outgrow a phone's width, so the row scrolls.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(RowSpacing),
        ) {
            options.forEach { option ->
                val label = stringResource(labelOf(option))
                OptionChip(
                    text = label,
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.testTag(chipTag(label)),
                )
            }
        }
    }
}
