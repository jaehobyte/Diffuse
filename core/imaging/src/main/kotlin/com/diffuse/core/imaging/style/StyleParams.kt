package com.diffuse.core.imaging.style

import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.model.HslTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * specs/style_match.md §3.1 — the conversion table, and **the only place either scale is named**.
 * `styles.json` speaks Lightroom (exposure in stops, everything else −100..100); `Operation.Adjust`
 * speaks −1..1. Nothing outside this file knows that.
 */
internal object StyleParams {

    /**
     * `LightOps.EXPOSURE_STOPS`: `AdjustKind.Exposure` spans ±2 EV, so a preset's stops divide by
     * it. The two constants have to agree, and `StyleParamsTest` is where that is asserted.
     */
    private const val EXPOSURE_STOPS_PER_UNIT = 2f

    /** Lightroom's own full-scale for every other scalar. */
    private const val LIGHTROOM_FULL_SCALE = 100f

    /**
     * §3.1: out of T71's scope. Grain is noise synthesis, colour grading is three tints rather than
     * a scalar, dehaze is a research problem, and `bw_filter` serves one style. A preset carrying
     * one of these drops it and keeps the rest.
     */
    val DROPPED = setOf("grain", "color_grading", "dehaze", "bw_filter")

    /** §3: intensity is not a parameter. One variant carries it inside `params` anyway. */
    private const val INTENSITY = "intensity"

    private const val HSL = "hsl"

    private val SCALARS = mapOf(
        "exposure" to Scale(AdjustKind.Exposure, EXPOSURE_STOPS_PER_UNIT),
        "contrast" to Scale(AdjustKind.Contrast, LIGHTROOM_FULL_SCALE),
        "highlights" to Scale(AdjustKind.Highlights, LIGHTROOM_FULL_SCALE),
        "shadows" to Scale(AdjustKind.Shadows, LIGHTROOM_FULL_SCALE),
        "blacks" to Scale(AdjustKind.Blacks, LIGHTROOM_FULL_SCALE),
        "whites" to Scale(AdjustKind.Whites, LIGHTROOM_FULL_SCALE),
        "fade" to Scale(AdjustKind.Fade, LIGHTROOM_FULL_SCALE),
        "s_curve" to Scale(AdjustKind.SCurve, LIGHTROOM_FULL_SCALE),
        "clarity" to Scale(AdjustKind.Clarity, LIGHTROOM_FULL_SCALE),
        "temperature" to Scale(AdjustKind.Temperature, LIGHTROOM_FULL_SCALE),
        "tint" to Scale(AdjustKind.Tint, LIGHTROOM_FULL_SCALE),
        "saturation" to Scale(AdjustKind.Saturation, LIGHTROOM_FULL_SCALE),
        "vibrance" to Scale(AdjustKind.Vibrance, LIGHTROOM_FULL_SCALE),
        "sharpen" to Scale(AdjustKind.Sharpen, LIGHTROOM_FULL_SCALE),
        // Lightroom darkens corners on a negative amount; `DetailOps.vignette` darkens on a
        // positive one over 0..1. The sign flip is the conversion, not a preset's opinion.
        "vignette" to Scale(AdjustKind.Vignette, -LIGHTROOM_FULL_SCALE),
    )

    private val BANDS = HslBand.entries.associateBy { it.name.lowercase() }

    private val CHANNELS = mapOf(
        "hue" to HslChannel.Hue,
        "sat" to HslChannel.Saturation,
        "lum" to HslChannel.Luminance,
    )

    private val HSL_KINDS = AdjustKind.entries
        .mapNotNull { kind -> kind.hsl?.let { it to kind } }
        .toMap()

    /**
     * One `styles.json` parameter becomes zero or more `AdjustKind`s: a scalar becomes one, `hsl`
     * becomes one per band and channel, and a dropped key becomes none.
     */
    fun convert(key: String, value: JsonElement): List<Pair<AdjustKind, Float>> = when {
        key in DROPPED || key == INTENSITY -> emptyList()
        key == HSL -> hslOf(value as JsonObject)
        else -> {
            val scale = SCALARS[key] ?: error("styles.json parameter '$key' is not in §3.1's table")
            listOf(scale.kind to scale.of(value.float()))
        }
    }

    private fun hslOf(bands: JsonObject): List<Pair<AdjustKind, Float>> =
        bands.entries.flatMap { (bandName, channels) ->
            val band = BANDS[bandName] ?: error("styles.json hsl band '$bandName' is unknown")
            (channels as JsonObject).entries.map { (channelName, amount) ->
                val channel = CHANNELS[channelName]
                    ?: error("styles.json hsl channel '$channelName' is unknown")
                val kind = HSL_KINDS.getValue(HslTarget(band, channel))
                kind to amount.float() / LIGHTROOM_FULL_SCALE
            }
        }

    private fun JsonElement.float(): Float =
        jsonPrimitive.floatOrNull ?: error("styles.json parameter is not a number: $this")

    private class Scale(val kind: AdjustKind, private val divisor: Float) {
        fun of(value: Float): Float = value / divisor
    }
}
