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
