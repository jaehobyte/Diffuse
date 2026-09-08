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

/**
 * Split by the sheet each kind belongs to, for the reason `Ops.globalAdjust` is: T71's five took
 * one `when` past detekt's complexity ceiling, and this is a lookup table rather than logic.
 */
@StringRes
private fun AdjustKind.globalLabelRes(): Int = lightLabelRes()
    ?: colorLabelRes()
    ?: detailLabelRes()
    ?: error("$name carries an HslTarget; its label is its channel's")

/**
 * specs/adjust_light.md, and T71's four. No sheet offers those four yet — a preset and the auto
 * boost set them — but the rule above still holds: a kind with no label cannot reach the 지시 step
 * list either.
 */
@StringRes
private fun AdjustKind.lightLabelRes(): Int? = when (this) {
    AdjustKind.Exposure -> R.string.light_exposure
    AdjustKind.Contrast -> R.string.light_contrast
    AdjustKind.Highlights -> R.string.light_highlights
    AdjustKind.Shadows -> R.string.light_shadows
    AdjustKind.Blacks -> R.string.light_blacks
    AdjustKind.Whites -> R.string.light_whites
    AdjustKind.Fade -> R.string.light_fade
    AdjustKind.SCurve -> R.string.light_s_curve
    else -> null
}

@StringRes
private fun AdjustKind.colorLabelRes(): Int? = when (this) {
    AdjustKind.Temperature -> R.string.color_temperature
    AdjustKind.Tint -> R.string.color_tint
    AdjustKind.Saturation -> R.string.color_saturation
    AdjustKind.Vibrance -> R.string.color_vibrance
    else -> null
}

@StringRes
private fun AdjustKind.detailLabelRes(): Int? = when (this) {
    AdjustKind.Sharpen -> R.string.detail_sharpen
    AdjustKind.Vignette -> R.string.detail_vignette
    AdjustKind.Clarity -> R.string.detail_clarity
    else -> null
}
