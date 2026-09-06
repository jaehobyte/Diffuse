package com.diffuse.feature.editor.tools

import androidx.annotation.StringRes
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.feature.editor.R

/**
 * DESIGN.md §9: user-facing strings live in strings.xml. One entry per kind, so a new
 * adjustment cannot reach the UI without a label.
 */
@StringRes
internal fun AdjustKind.labelRes(): Int = hsl?.let { it.channel.labelRes() } ?: globalLabelRes()

/**
 * specs/adjust_hsl.md §8: a 혼합 slider is labelled by its channel, because the sheet's chip
 * already names the band. The 지시 step list, which has no chip, composes the two.
 */
@StringRes
private fun HslChannel.labelRes(): Int = when (this) {
    HslChannel.Hue -> R.string.mix_hue
    HslChannel.Saturation -> R.string.mix_saturation
    HslChannel.Luminance -> R.string.mix_luminance
}

@StringRes
private fun AdjustKind.globalLabelRes(): Int = when (this) {
    AdjustKind.Exposure -> R.string.light_exposure
    AdjustKind.Contrast -> R.string.light_contrast
    AdjustKind.Highlights -> R.string.light_highlights
    AdjustKind.Shadows -> R.string.light_shadows
    AdjustKind.Temperature -> R.string.color_temperature
    AdjustKind.Tint -> R.string.color_tint
    AdjustKind.Saturation -> R.string.color_saturation
    AdjustKind.Vibrance -> R.string.color_vibrance
    AdjustKind.Sharpen -> R.string.detail_sharpen
    AdjustKind.Vignette -> R.string.detail_vignette

    else -> error("$name carries an HslTarget; its label is its channel's")
}
