package com.diffuse.feature.editor.tools.crop

import com.diffuse.core.ai.CropRatio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * specs/vibe_edit.md §4.1: the planner names a ratio, `feature:editor` is the only module that
 * knows what a rect at that ratio looks like. T68 added the two feed shapes to both sides.
 */
class CropRatioPresetTest {

    @Test
    fun `every planner ratio maps to the preset with the same number`() {
        val expected = mapOf(
            CropRatio.Square to (1f to AspectPreset.Square),
            CropRatio.Portrait3x4 to (3f / 4f to AspectPreset.ThreeFour),
            CropRatio.Portrait4x5 to (4f / 5f to AspectPreset.FourFive),
            CropRatio.Story9x16 to (9f / 16f to AspectPreset.NineSixteen),
            CropRatio.Landscape4x3 to (4f / 3f to AspectPreset.FourThree),
            CropRatio.Landscape16x9 to (16f / 9f to AspectPreset.SixteenNine),
        )

        // Every entry, so a ratio added to the enum without a preset fails here rather than on a
        // device: the `when` is exhaustive, but nothing else checks it maps to the right one.
        assertEquals(CropRatio.entries.size, expected.size)
        expected.forEach { (ratio, want) ->
            val (number, preset) = want
            assertEquals(ratio.name, preset, ratio.preset)
            assertEquals(ratio.name, number, preset.ratio!!, TOLERANCE)
        }
    }

    /** Every preset a plan can reach has a label; 자유 is the one no plan can ask for. */
    @Test
    fun `every preset has its own chip label`() {
        val labels = AspectPreset.entries.map(::presetLabelRes)

        assertEquals(AspectPreset.entries.size, labels.toSet().size)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
