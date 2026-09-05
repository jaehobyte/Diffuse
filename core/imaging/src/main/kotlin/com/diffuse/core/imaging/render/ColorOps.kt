package com.diffuse.core.imaging.render

import android.graphics.Bitmap

/**
 * specs/adjust_color.md §Math.
 *
 * Saturation reproduces `ColorMatrix.setSaturation` exactly — the same 0.213/0.715/0.072
 * weights and the same interpolation — rather than routing pixels through a Canvas. It
 * keeps every op on one code path, and specs/render.md wants the maths in `Ops.kt` so the
 * AGSL port (D03) can replace it in one place.
 */
internal object ColorOps {

    private const val TEMPERATURE_SHIFT = 0.1f
    private const val TINT_SHIFT = 0.1f

    // ColorMatrix.setSaturation's luminance weights.
    private const val SAT_R = 0.213f
    private const val SAT_G = 0.715f
    private const val SAT_B = 0.072f

    /** `R += v × 0.1`, `B −= v × 0.1` */
    fun temperature(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val shift = value * TEMPERATURE_SHIFT
        return bitmap.mapPixels { r, g, b -> packRgb(r + shift, g, b - shift) }
    }

    /** `G −= v × 0.1` */
    fun tint(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val shift = value * TINT_SHIFT
        return bitmap.mapPixels { r, g, b -> packRgb(r, g - shift, b) }
    }

    /** `ColorMatrix.setSaturation(1 + v)`; v = −1 is greyscale. */
    fun saturation(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        val saturation = 1f + value
        return bitmap.mapPixels { r, g, b -> applySaturation(r, g, b, saturation) }
    }

    /**
     * Saturation weighted by how unsaturated the pixel already is, so a flat sky moves and
     * a saturated red barely does.
     */
    fun vibrance(bitmap: Bitmap, value: Float): Bitmap {
        if (value == 0f) return bitmap
        return bitmap.mapPixels { r, g, b ->
            val existing = maxOf(r, g, b) - minOf(r, g, b)
            applySaturation(r, g, b, 1f + value * (1f - existing))
        }
    }

    private fun applySaturation(r: Float, g: Float, b: Float, saturation: Float): Int {
        val inverse = 1f - saturation
        val weightedR = SAT_R * inverse
        val weightedG = SAT_G * inverse
        val weightedB = SAT_B * inverse
        return packRgb(
            (weightedR + saturation) * r + weightedG * g + weightedB * b,
            weightedR * r + (weightedG + saturation) * g + weightedB * b,
            weightedR * r + weightedG * g + (weightedB + saturation) * b,
        )
    }
}
