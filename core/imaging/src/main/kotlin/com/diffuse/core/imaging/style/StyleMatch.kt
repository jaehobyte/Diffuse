package com.diffuse.core.imaging.style

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslColor
import com.diffuse.core.imaging.render.Ops
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * specs/style_match.md §5 step 1 — the **local** matcher, and the reason 컬러 매칭 works offline
 * and costs nothing. It is not an optimisation to add after the model call; the model call is what
 * happens when this fails.
 *
 * §5 names `PhotoTune_v1/reference_style.py`'s `match_style_map` as the source of the maths. That
 * file was never imported, so the formulas below are derived instead from the ops themselves:
 * every one is the algebraic inverse of what `LightOps` and `ColorOps` do, measured against a
 * neutral photograph. Swapping in the original later changes [measure] and nothing else.
 */
object StyleMatch {

    /**
     * How far a preset may sit from the reference and still be offered. Distance is a weighted RMS
     * in the −1..1 parameter space, so 0.2 is "about a fifth of a slider out, on average". A first
     * calibration: §5 expects it to be tuned against real references on a device.
     */
    const val STYLE_MATCH_THRESHOLD = 0.2f

    /** An average photograph, and therefore the zero of every measurement below. */
    private const val NEUTRAL_LUMA = 0.5f
    private const val NEUTRAL_SD = 0.2f
    private const val NEUTRAL_CHROMA = 0.25f

    /** `LightOps.exposure` is a gain of `2^(v × 2)`, so a mean ratio inverts through log2. */
    private const val EXPOSURE_STOPS = 2f

    /** `ColorOps` shifts a channel by `v × 0.1`; temperature moves two channels apart. */
    private const val TEMPERATURE_SPREAD = 0.2f
    private const val TINT_SHIFT = 0.1f

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /**
     * §5: shadows and highlights are not measured. They are the tonal distribution `Contrast`
     * already carries most of, and a second, weaker estimate of the same thing would move a
     * preset's rank without adding information.
     */
    private val WEIGHTS = mapOf(
        AdjustKind.Exposure to 1f,
        AdjustKind.Contrast to 1f,
        AdjustKind.Temperature to 1f,
        AdjustKind.Tint to 0.5f,
        AdjustKind.Saturation to 1f,
        // §5's "per-colour treatment". Five global statistics cannot tell 시네마틱 틸 from 어반 힙:
        // what separates them is a teal-and-orange split, which is invisible to a whole-frame
        // mean. These two bands are the axis that split runs along, and adding them is what made
        // every preset rank itself first.
        AdjustKind.HslOrangeSaturation to 0.75f,
        AdjustKind.HslBlueSaturation to 0.75f,
    )

    /** The bands [measure] reports, and therefore the only ones a signature can differ on. */
    private val MEASURED_BANDS = mapOf(
        HslBand.Orange to AdjustKind.HslOrangeSaturation,
        HslBand.Blue to AdjustKind.HslBlueSaturation,
    )

    /**
     * The reference's look, estimated in the **same −1..1 space a preset carries** (§5), so the
     * two can be compared without either side knowing about the other.
     */
    fun measure(bitmap: Bitmap): Map<AdjustKind, Float> {
        val stats = Stats.of(bitmap)
        return mapOf(
            AdjustKind.Exposure to clamp(
                ln(stats.luma / NEUTRAL_LUMA) / ln(2f) / EXPOSURE_STOPS,
            ),
            AdjustKind.Contrast to clamp(stats.sd / NEUTRAL_SD - 1f),
            AdjustKind.Temperature to clamp(stats.warmth / TEMPERATURE_SPREAD),
            // `ColorOps.tint` *subtracts* from green, so a green-heavy photo measures negative.
            AdjustKind.Tint to clamp(-stats.greenness / TINT_SHIFT),
            AdjustKind.Saturation to clamp(stats.chroma / NEUTRAL_CHROMA - 1f),
        ) + MEASURED_BANDS.entries.associate { (band, kind) ->
            kind to clamp(stats.bandChroma.getValue(band) / NEUTRAL_CHROMA - 1f)
        }
    }

    /**
     * Every preset scored against the reference, nearest first. §5: a weighted distance in the
     * normalized parameter space.
     *
     * The preset side is **measured, not read**. Comparing against `preset.params` directly was
     * the first attempt and it ranked 클린 브라이트's own output as 비비드 팝: a preset carries
     * parameters [measure] does not estimate — `shadows`, `vibrance`, `fade`, the HSL bands — and
     * every one of them still moves the five statistics that are estimated. Measuring the preset's
     * own output puts both sides in the same space, so a parameter with no estimator of its own
     * still counts through the effect it has.
     */
    fun rank(reference: Bitmap, presets: List<StylePreset>): List<StyleDistance> {
        val measured = measure(reference)
        return presets
            .map { StyleDistance(it, distance(measured, signature(it))) }
            .sortedBy { it.distance }
    }

    /**
     * What a preset looks like, measured. Computed once per id off a canonical neutral frame —
     * 64×64 through a handful of ops, so the whole catalog is a few hundred thousand pixel
     * operations, once per process.
     */
    private fun signature(preset: StylePreset): Map<AdjustKind, Float> =
        signatures.getOrPut(preset.id) { measure(referenceFrame(preset)) }

    /**
     * The frame [preset]'s signature is measured from: the neutral frame with the preset applied.
     * Public because it is what a match *means* — "this reference looks like this" — and because
     * a caller outside this module cannot otherwise reproduce one.
     */
    fun referenceFrame(preset: StylePreset): Bitmap =
        preset.params.entries.fold(neutralFrame()) { frame, (kind, value) ->
            Ops.adjust(kind)(frame, value)
        }

    private val signatures = ConcurrentHashMap<String, Map<AdjustKind, Float>>()

    /**
     * The frame every signature is measured from, built to measure as **no style at all**: a luma
     * ramp with mean [NEUTRAL_LUMA] and standard deviation [NEUTRAL_SD], carrying a chroma that
     * cancels across the frame so red, green and blue average to the same value.
     */
    fun neutralFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(FRAME, FRAME, Bitmap.Config.ARGB_8888)
        val count = FRAME * FRAME
        val half = NEUTRAL_SD * SQRT_3
        for (index in 0 until count) {
            val luma = NEUTRAL_LUMA - half + 2f * half * index / (count - 1f)
            // A full sweep of hues, so every band has pixels to measure and the offsets — each of
            // them zero-mean — cancel across the frame. Red, green and blue therefore average to
            // the luma, which is what makes the neutral frame measure as no colour cast at all.
            val hue = (index % HUES) * (FULL_CIRCLE / HUES)
            val pure = HslColor.toRgb(hue, FULL_SATURATION, MID_LIGHTNESS)
            val red = Color.red(pure) / FULL_CHANNEL
            val green = Color.green(pure) / FULL_CHANNEL
            val blue = Color.blue(pure) / FULL_CHANNEL
            // Subtracting the hue's **luma**, not its mean, is what keeps the ramp's standard
            // deviation the ramp's: an offset that is zero-mean in RGB is not zero-mean in luma,
            // because luma weights green ten times blue, and the leftover showed up as +0.06 of
            // contrast on a frame that is supposed to measure as nothing at all.
            val pureLuma = LUMA_R * red + LUMA_G * green + LUMA_B * blue
            bitmap.setPixel(
                index % FRAME,
                index / FRAME,
                Color.rgb(
                    byte(luma + NEUTRAL_CHROMA * (red - pureLuma)),
                    byte(luma + NEUTRAL_CHROMA * (green - pureLuma)),
                    byte(luma + NEUTRAL_CHROMA * (blue - pureLuma)),
                ),
            )
        }
        return bitmap
    }

    private fun byte(value: Float): Int =
        (value * FULL_CHANNEL).toInt().coerceIn(0, MAX_CHANNEL)

    private fun distance(measured: Map<AdjustKind, Float>, preset: Map<AdjustKind, Float>): Float {
        var sum = 0f
        var total = 0f
        WEIGHTS.forEach { (kind, weight) ->
            val delta = (measured[kind] ?: 0f) - (preset[kind] ?: 0f)
            sum += weight * delta * delta
            total += weight
        }
        return sqrt(sum / total)
    }

    private fun clamp(value: Float): Float = value.coerceIn(-1f, 1f)

    private const val FRAME = 64
    private const val FULL_CHANNEL = 255f
    private const val MAX_CHANNEL = 255

    /** A pure hue: fully saturated, mid-lightness — the widest chroma a hue has. */
    private const val FULL_SATURATION = 1f
    private const val MID_LIGHTNESS = 0.5f

    /** Every 30°, so all eight `HslBand` centres are represented. */
    private const val HUES = 12
    private const val FULL_CIRCLE = 360f

    /** A uniform ramp of half-width h has standard deviation h/√3. */
    private val SQRT_3 = sqrt(3f)

    private class Stats(
        val luma: Float,
        val sd: Float,
        /** `meanR − meanB`: the warm/cool difference `ColorOps.temperature` moves. */
        val warmth: Float,
        /** `meanG − (meanR + meanB) / 2`: the axis `ColorOps.tint` moves. */
        val greenness: Float,
        val chroma: Float,
        /** Mean chroma of the pixels nearest each band's centre; 0 where a band has none. */
        val bandChroma: Map<HslBand, Float>,
    ) {
        companion object {
            fun of(bitmap: Bitmap): Stats {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var luma = 0.0
                var lumaSquared = 0.0
                var chroma = 0.0
                val bandSum = HslBand.entries.associateWith { 0.0 }.toMutableMap()
                val bandCount = HslBand.entries.associateWith { 0 }.toMutableMap()
                val hsl = FloatArray(HSL_COMPONENTS)
                for (argb in pixels) {
                    val r = Color.red(argb) / FULL
                    val g = Color.green(argb) / FULL
                    val b = Color.blue(argb) / FULL
                    val l = LUMA_R * r + LUMA_G * g + LUMA_B * b
                    red += r
                    green += g
                    blue += b
                    luma += l
                    lumaSquared += l.toDouble() * l
                    val pixelChroma = maxOf(r, g, b) - minOf(r, g, b)
                    chroma += pixelChroma
                    if (pixelChroma > 0f) {
                        HslColor.fromRgb(r, g, b, hsl)
                        val band = nearestBand(hsl[0])
                        bandSum[band] = bandSum.getValue(band) + pixelChroma
                        bandCount[band] = bandCount.getValue(band) + 1
                    }
                }
                val n = pixels.size.toDouble()
                val meanLuma = luma / n
                val variance = (lumaSquared / n - meanLuma * meanLuma).coerceAtLeast(0.0)
                return Stats(
                    luma = meanLuma.toFloat().coerceAtLeast(MIN_LUMA),
                    sd = sqrt(variance).toFloat(),
                    warmth = ((red - blue) / n).toFloat(),
                    greenness = ((green - (red + blue) / 2.0) / n).toFloat(),
                    chroma = (chroma / n).toFloat(),
                    bandChroma = HslBand.entries.associateWith { band ->
                        val count = bandCount.getValue(band)
                        if (count == 0) 0f else (bandSum.getValue(band) / count).toFloat()
                    },
                )
            }

            /** A pixel belongs to the band whose centre its hue is closest to, around the circle. */
            private fun nearestBand(hue: Float): HslBand = HslBand.entries.minBy { band ->
                val delta = kotlin.math.abs(hue - band.centerDeg)
                minOf(delta, CIRCLE - delta)
            }

            private const val CIRCLE = 360f
            private const val HSL_COMPONENTS = 3

            /** A frame that is entirely black has no exposure to measure; log2(0) is not a number. */
            private const val MIN_LUMA = 1e-4f
            private const val FULL = 255f
        }
    }
}

/** One preset and how far the reference sits from it. */
data class StyleDistance(val preset: StylePreset, val distance: Float) {

    /** §5: within the threshold, the local answer is offered and no model is asked. */
    val isNear: Boolean get() = distance <= StyleMatch.STYLE_MATCH_THRESHOLD
}
