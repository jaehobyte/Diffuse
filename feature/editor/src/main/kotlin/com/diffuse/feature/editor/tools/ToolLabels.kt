package com.diffuse.feature.editor.tools

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.tools.mix.labelRes

/**
 * DESIGN.md §9: user-facing strings live in strings.xml. One entry per kind, so a new
 * adjustment cannot reach the UI without a label.
 */
@StringRes
internal fun AdjustKind.labelRes(): Int = hsl?.let { it.channel.labelRes() } ?: globalLabelRes()

/**
 * The name a step list uses: a 혼합 kind needs its band, because there is no chip beside it to
 * say which colour "채도" belongs to. specs/adjust_hsl.md §8 — the join is a bare space rather
 * than a template because the app is Korean-only by decision (specs/testing.md §9); if that ever
 * changes, this is the one place a template goes.
 */
@Composable
internal fun AdjustKind.stepLabel(): String = hsl
    ?.let { stringResource(it.band.labelRes()) + " " + stringResource(labelRes()) }
    ?: stringResource(labelRes())

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
