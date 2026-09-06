package com.diffuse.feature.editor.tools.auto

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import java.util.Locale

const val AutoSheetTestTag = "AutoSheet"
const val AutoStyleRowTestTag = "AutoStyles"
const val AutoReasonTestTag = "AutoReason"

@StringRes
internal fun styleLabelRes(style: AutoStyle): Int = when (style) {
    AutoStyle.Balanced -> R.string.auto_style_balanced
    AutoStyle.Vibrant -> R.string.auto_style_vibrant
    AutoStyle.Retro -> R.string.auto_style_retro
}

/**
 * specs/auto_enhance.md §6. The sheet 자동 opens **after** its call, holding the result: three
 * chips, one slider, and the model's own reason.
 *
 * Changing a chip costs a call and says so by showing the progress overlay again; the 강도 slider
 * costs none, because it scales a plan that is already here.
 */
@Composable
fun AutoSheet(
    state: AutoState,
    onStyleChange: (AutoStyle) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.auto_title),
        onCancel = onCancel,
        onApply = onApply,
        applyEnabled = state.canApply,
        modifier = modifier.testTag(AutoSheetTestTag),
    ) {
        Row(
            modifier = Modifier
                .testTag(AutoStyleRowTestTag)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoStyle.entries.forEach { entry ->
                StyleChip(
                    style = entry,
                    selected = entry == state.style,
                    onClick = { onStyleChange(entry) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.auto_intensity),
                style = Typography.label,
                color = colors.inkSecondary,
            )
            AdjustSlider(
                value = state.intensity.toFloat(),
                range = 0f..AUTO_INTENSITY_MAX.toFloat(),
                // DESIGN.md §4: only a zero-centered adjustment gets the centre tick, and 강도
                // runs 0…100 — its neutral end is the left one.
                zeroCentered = false,
                onChange = { onIntensityChange(it.toInt()) },
                onChangeFinished = {},
                format = { String.format(Locale.US, "%.0f", it) },
            )
        }

        if (state.reason.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.auto_reason),
                    style = Typography.label,
                    color = colors.inkSecondary,
                )
                // §6 and open decision 2: the model reasons in English and this shows it in
                // English. It is evidence that the boost was chosen rather than applied, and
                // translating it would cost a second round trip on every tap.
                Text(
                    text = state.reason,
                    style = Typography.bodySm,
                    color = colors.inkSecondary,
                    modifier = Modifier.testTag(AutoReasonTestTag),
                )
            }
        }
    }
}

@Composable
private fun StyleChip(style: AutoStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(styleLabelRes(style))
    val shape = RoundedCornerShape(CHIP_RADIUS)
    Text(
        text = label,
        style = Typography.label,
        color = if (selected) colors.ink else colors.inkSecondary,
        modifier = Modifier
            .testTag(label)
            .height(CHIP_HEIGHT)
            .background(color = colors.surfaceRaised, shape = shape)
            // specs/adjust_hsl.md §7's ruling, reused: the ring is `editInk`, never the accent —
            // the sheet's one accent belongs to 적용 (DESIGN.md §1). Transparent when unselected,
            // so nothing reflows.
            .border(
                width = CHIP_RING,
                color = if (selected) colors.ink else Color.Transparent,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val CHIP_HEIGHT = 32.dp
private val CHIP_RADIUS = 16.dp
private val CHIP_RING = 2.dp
