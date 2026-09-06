package com.diffuse.core.imaging.model

import android.graphics.RectF

/** specs/edit_model.md. A file in app storage. */
@JvmInline
value class ImageRef(val path: String)

/** specs/edit_model.md. Zero-centred kinds live in [-1, 1]; Sharpen and Vignette in [0, 1]. */
enum class AdjustKind(val range: ClosedFloatingPointRange<Float>) {
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
