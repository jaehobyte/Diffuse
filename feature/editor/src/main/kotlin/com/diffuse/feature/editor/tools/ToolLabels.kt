package com.diffuse.feature.editor.tools

import androidx.annotation.StringRes
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.feature.editor.R

/**
 * DESIGN.md §9: user-facing strings live in strings.xml. One entry per kind, so a new
 * adjustment cannot reach the UI without a label.
 */
@StringRes
internal fun AdjustKind.labelRes(): Int = when (this) {
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
}
