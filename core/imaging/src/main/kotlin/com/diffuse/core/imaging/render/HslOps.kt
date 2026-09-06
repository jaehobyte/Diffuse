package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.model.HslColor
import com.diffuse.core.imaging.model.HslTarget

/**
 * specs/adjust_hsl.md §4. Per-colour adjustment: a band weight decides how much of a pixel
 * belongs to the band being edited, and the channel decides what happens to it.
 *
 * The maths lives beside the other ops so the AGSL port (specs/render.md, D03) stays a
 * replacement of this package rather than a rewrite.
 */
internal object HslOps {

    /** The tightest band spacing in §2's table, so v = ±1 reaches the next centre and no further. */
    private const val HUE_SHIFT_DEG = 30f

    /** ±0.5 EV at full weight, the exposure idiom `LightOps` already uses. */
    private const val LUMINANCE_EV = 0.5f

    // Hue is meaningless for a grey pixel and unstable near grey, so near-neutrals are excluded.
    private const val NEUTRAL_EDGE0 = 0.05f
    private const val NEUTRAL_EDGE1 = 0.20f

    private const val FULL_CIRCLE = 360f
    private const val HALF_CIRCLE = 180f

    /** Hue, saturation, lightness — one scratch array per pass, never one per pixel. */
    private const val COMPONENTS = 3

    fun apply(target: HslTarget, bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val hsl = FloatArray(COMPONENTS)
        return bitmap.mapPixels { r, g, b ->
            HslColor.fromRgb(r, g, b, hsl)
            val weight = bandWeight(target.band, hsl[0]) *
                smoothstep(NEUTRAL_EDGE0, NEUTRAL_EDGE1, hsl[1])
            when {
                weight == 0f -> packRgb(r, g, b)

                target.channel == HslChannel.Hue -> HslColor.toRgb(
                    HslColor.wrapHue(hsl[0] + value * HUE_SHIFT_DEG * weight),
                    hsl[1],
                    hsl[2],
                )

                target.channel == HslChannel.Saturation -> HslColor.toRgb(
                    hsl[0],
                    (hsl[1] * (1f + value * weight)).coerceIn(0f, 1f),
                    hsl[2],
                )

                else -> {
                    val gain = exposureGain(value * weight * LUMINANCE_EV)
                    packRgb(r * gain, g * gain, b * gain)
                }
            }
        }
    }

    /**
     * specs/adjust_hsl.md §4: a linear tent between the neighbouring centres. The weights of all
     * eight bands sum to 1 for every hue, and a band's weight at any other band's centre is
     * exactly 0 — which is what lets "the other seven colours did not move" be a fact rather
     * than a tolerance.
     */
    fun bandWeight(band: HslBand, hueDeg: Float): Float {
        val bands = HslBand.entries
        val delta = signedDelta(hueDeg, band.centerDeg)
        return if (delta >= 0f) {
            val span = gap(band.centerDeg, bands[(band.ordinal + 1) % bands.size].centerDeg)
            if (delta >= span) 0f else 1f - delta / span
        } else {
            val previous = bands[(band.ordinal - 1 + bands.size) % bands.size]
            val span = gap(previous.centerDeg, band.centerDeg)
            if (-delta >= span) 0f else 1f + delta / span
        }
    }

    /** Degrees from [centerDeg] to [hueDeg] as a signed value in (−180, 180]. */
    private fun signedDelta(hueDeg: Float, centerDeg: Float): Float =
        (hueDeg - centerDeg + HALF_CIRCLE).mod(FULL_CIRCLE) - HALF_CIRCLE

    /** Degrees walking forward around the circle. */
    private fun gap(from: Float, to: Float): Float = (to - from).mod(FULL_CIRCLE)
}
