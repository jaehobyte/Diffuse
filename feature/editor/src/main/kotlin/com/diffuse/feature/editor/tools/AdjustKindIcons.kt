package com.diffuse.feature.editor.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.HdrStrong
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vignette
import androidx.compose.ui.graphics.vector.ImageVector
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslChannel

/**
 * DESIGN.md §4 Adjust sheet: the glyph inside a kind's circle, the way the Photo Editor's 색조 row
 * shows one per adjustment. Material Symbols Rounded only (DESIGN.md §7); a kind no sheet offers
 * falls back to the generic slider glyph.
 */
internal fun AdjustKind.icon(): ImageVector = hsl?.channel?.icon() ?: when (this) {
    AdjustKind.Exposure -> Icons.Rounded.Exposure
    AdjustKind.Contrast -> Icons.Rounded.Contrast
    AdjustKind.Highlights -> Icons.Rounded.LightMode
    AdjustKind.Shadows -> Icons.Rounded.Tonality
    AdjustKind.Temperature -> Icons.Rounded.Thermostat
    AdjustKind.Tint -> Icons.Rounded.InvertColors
    AdjustKind.Saturation -> Icons.Rounded.Opacity
    AdjustKind.Vibrance -> Icons.Rounded.Gradient
    AdjustKind.Sharpen -> Icons.Rounded.Texture
    AdjustKind.Vignette -> Icons.Rounded.Vignette
    AdjustKind.Clarity -> Icons.Rounded.HdrStrong
    else -> Icons.Rounded.Tune
}

private fun HslChannel.icon(): ImageVector = when (this) {
    HslChannel.Hue -> Icons.Rounded.Palette
    HslChannel.Saturation -> Icons.Rounded.Opacity
    HslChannel.Luminance -> Icons.Rounded.Brightness6
}
