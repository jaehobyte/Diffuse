package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/**
 * specs/adjust_light.md §Math. Every op is the identity at value 0, which is what makes
 * specs/edit_model.md's "neutral values are not stored" rule safe.
 */
internal object LightOps {

    private const val EXPOSURE_STOPS = 2f
    private const val HIGHLIGHT_EDGE0 = 0.6f
    private const val HIGHLIGHT_EDGE1 = 1.0f
    private const val SHADOW_EDGE0 = 0.0f
    private const val SHADOW_EDGE1 = 0.4f

    /**
     * T71: the endpoints sit **inside** the shadow and highlight bands and pull harder there.
     *
     * A tighter band alone is not enough to tell them apart: at near-black both weights are
     * already ~1, and `shadows`' gentler ramp is fractionally the higher of the two. What makes
     * an endpoint an endpoint is that it moves the last few values *further* than its sibling
     * can, so it gets its own stops factor as well as its own band.
     */
    private const val BLACK_EDGE1 = 0.2f
    private const val WHITE_EDGE0 = 0.8f
    private const val ENDPOINT_STOPS = 1.5f

    /** How far a full fade lifts the floor. A quarter is a matte, not a wash. */
    private const val FADE_LIFT = 0.25f

    /** `rgb × 2^(v × 2)` */
    fun exposure(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val gain = exposureGain(value * EXPOSURE_STOPS)
        return bitmap.mapPixels { r, g, b -> packRgb(r * gain, g * gain, b * gain) }
    }

    /** `(c − 0.5) × (1 + v) + 0.5` */
    fun contrast(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val scale = 1f + value
        return bitmap.mapPixels { r, g, b ->
            packRgb(
                (r - MID) * scale + MID,
                (g - MID) * scale + MID,
                (b - MID) * scale + MID,
            )
        }
    }

    /** `rgb × 2^(v × smoothstep(0.6, 1.0, luma))` */
    fun highlights(bitmap: Bitmap, value: Float): Bitmap =
        maskedExposure(bitmap, value) { luma ->
            smoothstep(HIGHLIGHT_EDGE0, HIGHLIGHT_EDGE1, luma)
        }

    /** `rgb × 2^(v × (1 − smoothstep(0.0, 0.4, luma)))` */
    fun shadows(bitmap: Bitmap, value: Float): Bitmap =
        maskedExposure(bitmap, value) { luma ->
            1f - smoothstep(SHADOW_EDGE0, SHADOW_EDGE1, luma)
        }

    /**
     * T71, specs/style_match.md §3.1. `rgb × 2^(v × 1.5 × (1 − smoothstep(0.0, 0.2, luma)))`
     *
     * The tone curve's dark endpoint. It is [shadows] with a tighter band rather than a second
     * kind of curve — the two are the same operation asked at different depths, and one curve is
     * what keeps a preset that sets both from fighting itself.
     */
    fun blacks(bitmap: Bitmap, value: Float): Bitmap =
        maskedExposure(bitmap, value) { luma ->
            ENDPOINT_STOPS * (1f - smoothstep(SHADOW_EDGE0, BLACK_EDGE1, luma))
        }

    /** T71. `rgb × 2^(v × 1.5 × smoothstep(0.8, 1.0, luma))` — [highlights]' endpoint. */
    fun whites(bitmap: Bitmap, value: Float): Bitmap =
        maskedExposure(bitmap, value) { luma ->
            ENDPOINT_STOPS * smoothstep(WHITE_EDGE0, HIGHLIGHT_EDGE1, luma)
        }

    /**
     * T71. `lift + c × (1 − lift)`, `lift = v × 0.25` — the matte look three of
     * specs/style_match.md's styles are defined by.
     *
     * Symmetric on purpose: a positive value raises the floor towards grey, and a negative one
     * pushes it below zero, where `packRgb` clamps it into deeper blacks. That makes 0 the
     * identity and the whole range monotonic, which is what edit_model.md's "neutral values are
     * not stored" rule needs.
     */
    fun fade(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val lift = value * FADE_LIFT
        val scale = 1f - lift
        return bitmap.mapPixels { r, g, b ->
            packRgb(lift + r * scale, lift + g * scale, lift + b * scale)
        }
    }

    /**
     * T71. `c + v × (smoothstep(0, 1, c) − c)` — one curve rather than two chained adjusts.
     *
     * [contrast] pivots the whole range around 0.5 linearly, which clips both ends before it has
     * bitten in the middle. This bends instead: the midtones steepen and the ends flatten, which
     * is what a photographer means by contrast and what `s_curve` names in a preset.
     */
    fun sCurve(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        return bitmap.mapPixels { r, g, b ->
            packRgb(bend(r, value), bend(g, value), bend(b, value))
        }
    }

    private fun bend(channel: Float, value: Float): Float =
        channel + value * (smoothstep(0f, 1f, channel) - channel)

    private inline fun maskedExposure(
        bitmap: Bitmap,
        value: Float,
        crossinline weight: (luma: Float) -> Float,
    ): Bitmap {
        if (value == 0f) return bitmap
        return bitmap.mapPixels { r, g, b ->
            val gain = exposureGain(value * weight(luma(r, g, b)))
            packRgb(r * gain, g * gain, b * gain)
        }
    }

    private const val MID = 0.5f
}
