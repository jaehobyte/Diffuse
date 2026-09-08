package com.diffuse.core.ai.monet

import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel

/**
 * specs/auto_enhance.md §3. MonetGPT's Lightroom operation names, mapped onto `AdjustKind`.
 *
 * The HSL half is an **exact** match — the same eight bands and the same three channels
 * adjust_hsl.md §10 gave `AdjustKind` — which is worth noticing rather than assuming: it means
 * T54's 24 entries were the right 24. The tone half needed `Blacks` and `Whites`, which is what
 * T71 added.
 *
 * Derived from `HslBand` and `HslChannel` rather than written out, so a band added to one is
 * added to both; `MonetOperationsTest` asserts the whole list against MonetGPT's own config.
 */
internal val MONET_OPERATIONS: Map<String, AdjustKind> = buildMap {
    put("Exposure", AdjustKind.Exposure)
    put("Contrast", AdjustKind.Contrast)
    put("Highlights", AdjustKind.Highlights)
    put("Shadows", AdjustKind.Shadows)
    put("Blacks", AdjustKind.Blacks)
    put("Whites", AdjustKind.Whites)
    put("Temperature", AdjustKind.Temperature)
    put("Tint", AdjustKind.Tint)
    put("Saturation", AdjustKind.Saturation)
    HslBand.entries.forEach { band ->
        HslChannel.entries.forEach { channel ->
            put("${channel.monetPrefix}Adjustment${band.name}", hslKind(band, channel))
        }
    }
}

/** MonetGPT spells the channel first: `HueAdjustmentAqua`, `LuminanceAdjustmentBlue`. */
private val HslChannel.monetPrefix: String
    get() = when (this) {
        HslChannel.Hue -> "Hue"
        HslChannel.Saturation -> "Saturation"
        HslChannel.Luminance -> "Luminance"
    }

private fun hslKind(band: HslBand, channel: HslChannel): AdjustKind =
    AdjustKind.entries.first { it.hsl?.band == band && it.hsl?.channel == channel }

/** §3: "All adjustment values are scaled between -100 and +100"; `Adjust.value` is −1..1. */
internal const val MONET_VALUE_SCALE = 100f
