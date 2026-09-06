package com.diffuse.feature.editor.tools.mix

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.model.HslColor
import com.diffuse.core.imaging.model.HslTarget
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.AdjustSheet
import com.diffuse.feature.editor.tools.MaskOption

/** DESIGN.md §5: the spacing scale, and §4's 48dp minimum touch target. */
private val ChipWidth = 48.dp
private val ChipSpacing = 12.dp
private val SwatchSize = 32.dp
private val RingSize = 42.dp
private val RingWidth = 2.dp
private val LabelSpacing = 4.dp

/** specs/adjust_hsl.md §6: the swatch is the band's own colour, at a readable saturation. */
private const val SWATCH_SATURATION = 0.7f
private const val SWATCH_LIGHTNESS = 0.5f
private const val OPAQUE = 0xFF000000

/** specs/adjust_hsl.md §6: 색조 → 채도 → 휘도 for the selected band. */
fun mixKinds(band: HslBand): List<AdjustKind> = HslChannel.entries.map { channel ->
    AdjustKind.entries.first { it.hsl == HslTarget(band, channel) }
}

fun mixBandChipTag(band: HslBand): String = "MixBandChip:${band.name}"

/**
 * The chip fill. Derived from the band's own centre through the same conversion the maths uses,
 * so a swatch cannot drift from the pixels its sliders move.
 */
internal fun HslBand.swatchColor(): Color =
    Color(HslColor.toRgb(centerDeg, SWATCH_SATURATION, SWATCH_LIGHTNESS) or OPAQUE.toInt())

@StringRes
internal fun HslBand.labelRes(): Int = when (this) {
    HslBand.Red -> R.string.mix_band_red
    HslBand.Orange -> R.string.mix_band_orange
    HslBand.Yellow -> R.string.mix_band_yellow
    HslBand.Green -> R.string.mix_band_green
    HslBand.Aqua -> R.string.mix_band_aqua
    HslBand.Blue -> R.string.mix_band_blue
    HslBand.Purple -> R.string.mix_band_purple
    HslBand.Magenta -> R.string.mix_band_magenta
}

/**
 * specs/adjust_hsl.md §6. 혼합 is the generic [AdjustSheet] with a band chip row as its header
 * and the selected band's three kinds as its sliders — not a second sheet (adjust_color.md).
 *
 * The selected band is view state: the values themselves live in the document, and the sliders
 * read them, so re-opening on 빨강 loses nothing.
 */
@Composable
fun MixSheet(
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    maskOption: MaskOption = MaskOption.None,
) {
    var band by rememberSaveable { mutableStateOf(HslBand.Red) }

    AdjustSheet(
        title = stringResource(R.string.mix_title),
        kinds = mixKinds(band),
        document = document,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        onCancel = onCancel,
        onApply = onApply,
        modifier = modifier,
        maskOption = maskOption,
        header = { BandChipRow(selected = band, onSelect = { band = it }) },
    )
}

@Composable
private fun BandChipRow(selected: HslBand, onSelect: (HslBand) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        HslBand.entries.forEach { band ->
            BandChip(band = band, selected = band == selected, onClick = { onSelect(band) })
        }
    }
}

@Composable
private fun BandChip(band: HslBand, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .width(ChipWidth)
            .clickable(onClick = onClick)
            .testTag(mixBandChipTag(band)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LabelSpacing),
    ) {
        Box(
            modifier = Modifier
                .size(RingSize)
                // specs/adjust_hsl.md §7: the ring is `editInk`, never the accent — the sheet's
                // one accent belongs to 적용. Transparent when unselected, so nothing reflows.
                .border(
                    width = RingWidth,
                    color = if (selected) colors.ink else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(SwatchSize)
                    .clip(CircleShape)
                    .background(band.swatchColor()),
            )
        }
        Text(
            text = stringResource(band.labelRes()),
            style = Typography.label,
            color = if (selected) colors.ink else colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
