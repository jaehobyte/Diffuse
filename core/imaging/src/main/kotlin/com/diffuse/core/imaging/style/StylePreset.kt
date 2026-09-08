package com.diffuse.core.imaging.style

import com.diffuse.core.imaging.model.AdjustKind

/**
 * specs/style_match.md §3. A style is a list of slider positions, not a LUT (ADR-014), so a preset
 * carries nothing a user could not have dialled in by hand.
 *
 * The Korean name is deliberately absent: `core:imaging` has no resources, so the `id` is the join
 * and `feature:editor` owns the string — the boundary `CropRatio` already crosses (T58).
 */
data class StylePreset(
    val id: String,
    val warmth: Float,
    val hardness: Float,
    val params: Map<AdjustKind, Float>,
    val variants: List<StyleVariant>,
)

/**
 * A second decision, not a second style (§3): film grain versus film colour. Its `id` is derived
 * from its style's, because `styles.json` gives a variant a Korean name and no key of its own.
 */
data class StyleVariant(
    val id: String,
    val params: Map<AdjustKind, Float>,
)

/**
 * §3: `intensity` is not a parameter, it scales the whole preset — `value * intensity / 100`.
 * A scaled-to-zero parameter is dropped rather than committed, so intensity 0 is the identity in
 * the document as well as in the arithmetic.
 */
fun Map<AdjustKind, Float>.atIntensity(intensity: Int): Map<AdjustKind, Float> =
    mapValues { (_, value) -> value * intensity / FULL_INTENSITY }
        .filterNot { (kind, value) -> kind.isNeutral(value) }

private const val FULL_INTENSITY = 100f
