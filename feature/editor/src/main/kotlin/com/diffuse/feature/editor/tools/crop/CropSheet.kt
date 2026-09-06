package com.diffuse.feature.editor.tools.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.components.AdjustSlider
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import java.util.Locale

/** specs/crop.md: straighten runs −45…45°, shown with one decimal. */
const val STRAIGHTEN_MIN_DEG = -45f
const val STRAIGHTEN_MAX_DEG = 45f

const val CropPresetRowTestTag = "CropPresets"
const val RotateLeftTestTag = "CropRotateLeft"
const val RotateRightTestTag = "CropRotateRight"

fun presetLabelRes(preset: AspectPreset): Int = when (preset) {
    AspectPreset.Free -> R.string.crop_free
    AspectPreset.Square -> R.string.crop_square
    AspectPreset.ThreeFour -> R.string.crop_three_four
    AspectPreset.FourThree -> R.string.crop_four_three
    AspectPreset.FourFive -> R.string.crop_four_five
    AspectPreset.NineSixteen -> R.string.crop_nine_sixteen
    AspectPreset.SixteenNine -> R.string.crop_sixteen_nine
}

@Composable
fun CropSheet(
    preset: AspectPreset,
    straightenDeg: Float,
    onPresetChange: (AspectPreset) -> Unit,
    onStraightenChange: (Float) -> Unit,
    onStraightenFinished: () -> Unit,
    onRotate: (Int) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    EditSheet(
        title = stringResource(R.string.crop_title),
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
    ) {
        // T68: seven presets no longer fit a phone's width, so the row scrolls — the same
        // answer DESIGN.md §4 already gives the tool strip.
        Row(
            modifier = Modifier
                .testTag(CropPresetRowTestTag)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AspectPreset.entries.forEach { entry ->
                PresetChip(
                    preset = entry,
                    selected = entry == preset,
                    onClick = { onPresetChange(entry) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.crop_straighten),
                style = Typography.label,
                color = colors.inkSecondary,
            )
            AdjustSlider(
                value = straightenDeg,
                range = STRAIGHTEN_MIN_DEG..STRAIGHTEN_MAX_DEG,
                zeroCentered = true,
                onChange = onStraightenChange,
                onChangeFinished = onStraightenFinished,
                format = { String.format(Locale.US, "%.1f°", it) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RotateButton(
                testTag = RotateLeftTestTag,
                labelRes = R.string.crop_rotate_left,
                onClick = { onRotate(-1) },
            ) { label -> Icon(Icons.AutoMirrored.Rounded.RotateLeft, label, tint = colors.ink) }
            RotateButton(
                testTag = RotateRightTestTag,
                labelRes = R.string.crop_rotate_right,
                onClick = { onRotate(1) },
            ) { label -> Icon(Icons.Rounded.RotateRight, label, tint = colors.ink) }
        }
    }
}

@Composable
private fun PresetChip(preset: AspectPreset, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(presetLabelRes(preset))
    Text(
        text = label,
        style = Typography.label,
        color = if (selected) Tokens.onAccent else colors.ink,
        modifier = Modifier
            .testTag(label)
            .height(32.dp)
            .background(
                color = if (selected) Tokens.accent else colors.surfaceRaised,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RotateButton(
    testTag: String,
    labelRes: Int,
    onClick: () -> Unit,
    icon: @Composable (contentDescription: String) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .testTag(testTag)
            .size(48.dp)
            .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(stringResource(labelRes))
    }
}
