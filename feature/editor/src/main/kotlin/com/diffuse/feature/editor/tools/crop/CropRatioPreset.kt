package com.diffuse.feature.editor.tools.crop

import com.diffuse.core.ai.CropRatio

/**
 * specs/vibe_edit.md §4.1. The planner's closed set of ratios, mapped onto the preset the 자르기
 * chips already use — so the rect a plan commits comes from `CropGeometry.applyPreset`, the same
 * code path a tap on the chip takes, and the model contributes no geometry.
 *
 * The mapping lives on this side of the boundary on purpose: `core:ai` names the ratio, and
 * `feature:editor` is the only module that knows what a rect at that ratio looks like
 * (specs/ai_provider.md §3).
 */
val CropRatio.preset: AspectPreset
    get() = when (this) {
        CropRatio.Square -> AspectPreset.Square
        CropRatio.Portrait4x5 -> AspectPreset.FourFive
        CropRatio.Story9x16 -> AspectPreset.NineSixteen
        CropRatio.Landscape16x9 -> AspectPreset.SixteenNine
    }
