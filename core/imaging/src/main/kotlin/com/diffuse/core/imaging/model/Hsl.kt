package com.diffuse.core.imaging.model

import kotlin.math.abs

/**
 * specs/adjust_hsl.md §2. The eight hue bands 혼합 edits, and the only place their centres live.
 *
 * The spacing is deliberately uneven: skin, foliage and sky are where a photo editor's requests
 * land, so 빨강/주황/노랑 sit 30° apart while 초록→청록→파랑 are 60° apart. The weight function
 * spans whatever gap it is given (§4), so nothing downstream repeats these numbers.
 */
enum class HslBand(val centerDeg: Float) {
    Red(RED_DEG),
    Orange(ORANGE_DEG),
    Yellow(YELLOW_DEG),
    Green(GREEN_DEG),
    Aqua(AQUA_DEG),
    Blue(BLUE_DEG),
    Purple(PURPLE_DEG),
    Magenta(MAGENTA_DEG),
}

private const val RED_DEG = 0f
private const val ORANGE_DEG = 30f
private const val YELLOW_DEG = 60f
private const val GREEN_DEG = 120f
private const val AQUA_DEG = 180f
private const val BLUE_DEG = 240f
private const val PURPLE_DEG = 280f
private const val MAGENTA_DEG = 320f

/** specs/adjust_hsl.md §4. What a 혼합 slider moves inside its band. */
enum class HslChannel { Hue, Saturation, Luminance }

/** One 혼합 slider: a band and the channel it moves. */
data class HslTarget(val band: HslBand, val channel: HslChannel)

/**
 * specs/adjust_hsl.md §3. One RGB↔HSL conversion, shared by `HslOps` and the sheet's band chips,
 * so a swatch cannot drift from the band it names.
 *
 * The packing is repeated here rather than reused from `render.packRgb` because the model layer
 * does not depend on the render layer; the clamp rule is specs/render.md's, unchanged.
 */
object HslColor {

    private const val CHANNEL_MAX = 255f
    private const val CHANNEL_MAX_INT = 255
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val FULL_CIRCLE = 360f
    private const val SEGMENT = 60f
    private const val SEGMENTS = 6f

    // Which sixth of the circle a hue falls in, named so the RGB triple below reads as colours.
    private const val RED_TO_YELLOW = 0
    private const val YELLOW_TO_GREEN = 1
    private const val GREEN_TO_AQUA = 2
    private const val AQUA_TO_BLUE = 3
    private const val BLUE_TO_MAGENTA = 4

    // The sector offsets of the standard RGB→hue formula.
    private const val GREEN_OFFSET = 2f
    private const val BLUE_OFFSET = 4f

    /**
     * Hue in degrees, saturation and lightness in 0..1, out as RGB in the **low 24 bits** — the
     * alpha byte is left clear so a per-pixel transform keeps the source alpha.
     */
    fun toRgb(hueDeg: Float, saturation: Float, lightness: Float): Int {
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val sector = wrapHue(hueDeg) / SEGMENT
        val second = chroma * (1f - abs(sector.mod(2f) - 1f))
        val base = lightness - chroma / 2f
        val (r, g, b) = when (sector.toInt()) {
            RED_TO_YELLOW -> Triple(chroma, second, 0f)
            YELLOW_TO_GREEN -> Triple(second, chroma, 0f)
            GREEN_TO_AQUA -> Triple(0f, chroma, second)
            AQUA_TO_BLUE -> Triple(0f, second, chroma)
            BLUE_TO_MAGENTA -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        return (channel(r + base) shl RED_SHIFT) or (channel(g + base) shl GREEN_SHIFT) or
            channel(b + base)
    }

    /** Writes `[hue 0..360, saturation 0..1, lightness 0..1]` into [out], which the caller owns. */
    fun fromRgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        val lightness = (max + min) / 2f

        if (chroma == 0f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = lightness
            return
        }

        val hue = when (max) {
            r -> SEGMENT * ((g - b) / chroma).mod(SEGMENTS)
            g -> SEGMENT * ((b - r) / chroma + GREEN_OFFSET)
            else -> SEGMENT * ((r - g) / chroma + BLUE_OFFSET)
        }
        out[0] = wrapHue(hue)
        out[1] = chroma / (1f - abs(2f * lightness - 1f))
        out[2] = lightness
    }

    /** Degrees into `[0, 360)`, so a shift past either end stays a colour. */
    fun wrapHue(hueDeg: Float): Float = hueDeg.mod(FULL_CIRCLE)

    private fun channel(value: Float): Int =
        (value.coerceIn(0f, 1f) * CHANNEL_MAX).toInt().coerceIn(0, CHANNEL_MAX_INT)
}
