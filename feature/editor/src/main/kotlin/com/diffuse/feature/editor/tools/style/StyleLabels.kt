package com.diffuse.feature.editor.tools.style

import androidx.annotation.StringRes
import com.diffuse.feature.editor.R

/**
 * specs/style_match.md §3: `styles.json` carries Korean and it is ignored — DESIGN.md §9 puts
 * user-facing strings in `strings.xml`, and the preset's `id` is the join.
 *
 * `core:imaging` has no resources, so `StylePreset` cannot hold the `nameRes` §3 draws on it; the
 * name is attached here instead, at the same `feature:editor` boundary `CropRatio` crosses. An id
 * with no entry does not compile, which is stronger than the test §9 asks for — `StyleLabelsTest`
 * then asserts the map covers the catalog, so a *new* preset cannot arrive unnamed either.
 */
@StringRes
internal fun styleNameRes(id: String): Int = STYLE_NAMES.getValue(id)

private val STYLE_NAMES = mapOf(
    "clean-bright" to R.string.style_name_clean_bright,
    "natural-enhance" to R.string.style_name_natural_enhance,
    "crisp-landscape" to R.string.style_name_crisp_landscape,
    "film-warm" to R.string.style_name_film_warm,
    "faded-matte" to R.string.style_name_faded_matte,
    "cinematic-teal" to R.string.style_name_cinematic_teal,
    "moody-dark" to R.string.style_name_moody_dark,
    "vibrant-pop" to R.string.style_name_vibrant_pop,
    "pastel-soft" to R.string.style_name_pastel_soft,
    "golden-glow" to R.string.style_name_golden_glow,
    "urban-hip" to R.string.style_name_urban_hip,
    "bw-classic" to R.string.style_name_bw_classic,
)

/**
 * §3 again, for the second decision. A variant's id is derived (`<styleId>-<n>`) because
 * `styles.json` gives it a Korean name and no key of its own; that name lives here.
 */
@StringRes
internal fun styleVariantNameRes(id: String): Int = VARIANT_NAMES.getValue(id)

private val VARIANT_NAMES = mapOf(
    "clean-bright-1" to R.string.style_variant_clean_bright_1,
    "clean-bright-2" to R.string.style_variant_clean_bright_2,
    "clean-bright-3" to R.string.style_variant_clean_bright_3,
    "natural-enhance-1" to R.string.style_variant_natural_enhance_1,
    "natural-enhance-2" to R.string.style_variant_natural_enhance_2,
    "natural-enhance-3" to R.string.style_variant_natural_enhance_3,
    "crisp-landscape-1" to R.string.style_variant_crisp_landscape_1,
    "crisp-landscape-2" to R.string.style_variant_crisp_landscape_2,
    "crisp-landscape-3" to R.string.style_variant_crisp_landscape_3,
    "film-warm-1" to R.string.style_variant_film_warm_1,
    "film-warm-2" to R.string.style_variant_film_warm_2,
    "film-warm-3" to R.string.style_variant_film_warm_3,
    "film-warm-4" to R.string.style_variant_film_warm_4,
    "faded-matte-1" to R.string.style_variant_faded_matte_1,
    "faded-matte-2" to R.string.style_variant_faded_matte_2,
    "faded-matte-3" to R.string.style_variant_faded_matte_3,
    "cinematic-teal-1" to R.string.style_variant_cinematic_teal_1,
    "cinematic-teal-2" to R.string.style_variant_cinematic_teal_2,
    "cinematic-teal-3" to R.string.style_variant_cinematic_teal_3,
    "cinematic-teal-4" to R.string.style_variant_cinematic_teal_4,
    "moody-dark-1" to R.string.style_variant_moody_dark_1,
    "moody-dark-2" to R.string.style_variant_moody_dark_2,
    "moody-dark-3" to R.string.style_variant_moody_dark_3,
    "vibrant-pop-1" to R.string.style_variant_vibrant_pop_1,
    "vibrant-pop-2" to R.string.style_variant_vibrant_pop_2,
    "vibrant-pop-3" to R.string.style_variant_vibrant_pop_3,
    "pastel-soft-1" to R.string.style_variant_pastel_soft_1,
    "pastel-soft-2" to R.string.style_variant_pastel_soft_2,
    "pastel-soft-3" to R.string.style_variant_pastel_soft_3,
    "golden-glow-1" to R.string.style_variant_golden_glow_1,
    "golden-glow-2" to R.string.style_variant_golden_glow_2,
    "urban-hip-1" to R.string.style_variant_urban_hip_1,
    "urban-hip-2" to R.string.style_variant_urban_hip_2,
    "urban-hip-3" to R.string.style_variant_urban_hip_3,
    "bw-classic-1" to R.string.style_variant_bw_classic_1,
    "bw-classic-2" to R.string.style_variant_bw_classic_2,
    "bw-classic-3" to R.string.style_variant_bw_classic_3,
    "bw-classic-4" to R.string.style_variant_bw_classic_4,
    "bw-classic-5" to R.string.style_variant_bw_classic_5,
    "bw-classic-6" to R.string.style_variant_bw_classic_6,
    "bw-classic-7" to R.string.style_variant_bw_classic_7,
)
