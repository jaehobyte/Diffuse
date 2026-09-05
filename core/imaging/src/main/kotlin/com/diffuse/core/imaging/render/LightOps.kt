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
