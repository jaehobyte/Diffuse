package com.diffuse.core.imaging.model

import android.graphics.RectF

/** specs/edit_model.md. A file in app storage. */
@JvmInline
value class ImageRef(val path: String)

/**
 * specs/edit_model.md. Zero-centred kinds live in [-1, 1]; Sharpen and Vignette in [0, 1].
 *
 * specs/adjust_hsl.md §3: a kind with an [hsl] target is one 혼합 slider. The target is a single
 * nullable field rather than a band and a channel, so a kind can never be half-declared and
 * `Ops` dispatches every one of them in one branch.
 */
enum class AdjustKind(
    val range: ClosedFloatingPointRange<Float>,
    val hsl: HslTarget? = null,
) {
    // Light
    Exposure(ZERO_CENTRED),
    Contrast(ZERO_CENTRED),
    Highlights(ZERO_CENTRED),
    Shadows(ZERO_CENTRED),

    // Color
    Temperature(ZERO_CENTRED),
    Tint(ZERO_CENTRED),
    Saturation(ZERO_CENTRED),
    Vibrance(ZERO_CENTRED),

    // Detail
    Sharpen(UNIT_RANGE),
    Vignette(UNIT_RANGE),

    // specs/adjust_hsl.md — 혼합, appended so no existing entry moves
    HslRedHue(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Hue)),
    HslRedSaturation(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Saturation)),
    HslRedLuminance(ZERO_CENTRED, HslTarget(HslBand.Red, HslChannel.Luminance)),
    HslOrangeHue(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Hue)),
    HslOrangeSaturation(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Saturation)),
    HslOrangeLuminance(ZERO_CENTRED, HslTarget(HslBand.Orange, HslChannel.Luminance)),
    HslYellowHue(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Hue)),
    HslYellowSaturation(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Saturation)),
    HslYellowLuminance(ZERO_CENTRED, HslTarget(HslBand.Yellow, HslChannel.Luminance)),
    HslGreenHue(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Hue)),
    HslGreenSaturation(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Saturation)),
    HslGreenLuminance(ZERO_CENTRED, HslTarget(HslBand.Green, HslChannel.Luminance)),
    HslAquaHue(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Hue)),
    HslAquaSaturation(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Saturation)),
    HslAquaLuminance(ZERO_CENTRED, HslTarget(HslBand.Aqua, HslChannel.Luminance)),
    HslBlueHue(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Hue)),
    HslBlueSaturation(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Saturation)),
    HslBlueLuminance(ZERO_CENTRED, HslTarget(HslBand.Blue, HslChannel.Luminance)),
    HslPurpleHue(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Hue)),
    HslPurpleSaturation(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Saturation)),
    HslPurpleLuminance(ZERO_CENTRED, HslTarget(HslBand.Purple, HslChannel.Luminance)),
    HslMagentaHue(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Hue)),
    HslMagentaSaturation(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Saturation)),
    HslMagentaLuminance(ZERO_CENTRED, HslTarget(HslBand.Magenta, HslChannel.Luminance)),
    ;

    /** 0 is neutral for every kind, so a zero value means "no operation". */
    fun isNeutral(value: Float): Boolean = value == 0f

    fun coerce(value: Float): Float = value.coerceIn(range)
}

private val ZERO_CENTRED = -1f..1f
private val UNIT_RANGE = 0f..1f

/**
 * specs/edit_model.md. Sealed so new ops arrive without touching the existing ones.
 */
sealed interface Operation {

    val id: String

    data class Adjust(
        override val id: String,
        val kind: AdjustKind,
        val value: Float,
        /**
         * specs/selection_tool.md §8.1: when set, the renderer blends this adjustment through
         * the named [Mask] instead of applying it to the whole frame.
         */
        val maskId: String? = null,
    ) : Operation

    /**
     * A selection. Changes no pixels on its own; other ops reference it by [id].
     *
     * It stores the resulting alpha and **not** the prompts that produced it: a v2 selection is
     * built by merging point runs and text phrases (specs/selection_tool.md §4), so no single
     * prompt reproduces it.
     */
    data class Mask(
        override val id: String,
        /** `ALPHA_8` PNG at working resolution, in the project folder. */
        val maskRef: ImageRef,
    ) : Operation

    /**
     * specs/selection_tool.md §8.2: clears the alpha outside [maskId], leaving a cut-out.
     * Several may stack; each one restricts the alpha further.
     */
    data class CutOut(override val id: String, val maskId: String) : Operation

    /**
     * specs/generative_erase.md §6. The one op that carries its own pixels: the renderer takes
     * [resultRef] inside [maskId] and leaves everything outside it alone, so the document stays
     * composable and undo is still a single removal.
     */
    data class GenerativeErase(
        override val id: String,
        val maskId: String,
        /** PNG at working resolution, in the project folder. */
        val resultRef: ImageRef,
    ) : Operation

    /** [rect] is normalised 0..1 against the un-cropped, un-rotated source. */
    data class Crop(
        override val id: String,
        val rect: RectF,
        val angleDeg: Float,
    ) : Operation {

        /** A full-frame, unrotated crop is a no-op and is never stored. */
        val isFullFrame: Boolean
            get() = angleDeg == 0f &&
                rect.left == 0f && rect.top == 0f && rect.right == 1f && rect.bottom == 1f
    }
}
