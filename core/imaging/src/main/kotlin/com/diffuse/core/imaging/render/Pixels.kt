package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import kotlin.math.pow

/** specs/adjust_light.md: Rec.709 luma over channels normalised to 0..1. */
internal fun luma(r: Float, g: Float, b: Float): Float =
    LUMA_R * r + LUMA_G * g + LUMA_B * b

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

internal const val CHANNEL_MAX = 255f

private const val CHANNEL_MASK = 0xFF
private const val CHANNEL_MAX_INT = 255
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val SMOOTHSTEP_A = 3f
private const val SMOOTHSTEP_B = 2f

/** The classic Hermite interpolation used by the highlight and shadow masks. */
internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (SMOOTHSTEP_A - SMOOTHSTEP_B * t)
}

internal fun exposureGain(stops: Float): Float = 2f.pow(stops)

/**
 * Applies a per-pixel transform, leaving alpha untouched. Inlined so the maths compiles
 * into the loop rather than a virtual call per pixel.
 */
internal inline fun Bitmap.mapPixels(
    crossinline transform: (r: Float, g: Float, b: Float) -> Int,
): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (index in pixels.indices) {
        val argb = pixels[index]
        val rgb = transform(
            ((argb shr RED_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX,
            ((argb shr GREEN_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX,
            (argb and CHANNEL_MASK) / CHANNEL_MAX,
        )
        pixels[index] = (argb and ALPHA_MASK.toInt()) or rgb
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private const val ALPHA_MASK = 0xFF000000u

/** Packs three 0..1 channels back into the low 24 bits, clamping as specs/render.md requires. */
internal fun packRgb(r: Float, g: Float, b: Float): Int =
    (channel(r) shl RED_SHIFT) or (channel(g) shl GREEN_SHIFT) or channel(b)

private fun channel(value: Float): Int =
    (value.coerceIn(0f, 1f) * CHANNEL_MAX).toInt().coerceIn(0, CHANNEL_MAX_INT)
