package com.diffuse.core.ai.monet

import com.diffuse.core.imaging.model.AdjustKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * specs/auto_enhance.md §3, §8: "the test that will fail the day the model's vocabulary changes".
 *
 * The expected names are MonetGPT's own, copied from `dataset/constants.py` and
 * `configs/inference_config.yaml` — spelled out rather than derived, because deriving them from
 * our own enum would make this test agree with itself.
 */
class MonetOperationsTest {

    @Test
    fun `every tone operation MonetGPT emits has a kind`() {
        val expected = mapOf(
            "Exposure" to AdjustKind.Exposure,
            "Contrast" to AdjustKind.Contrast,
            "Highlights" to AdjustKind.Highlights,
            "Shadows" to AdjustKind.Shadows,
            "Blacks" to AdjustKind.Blacks,
            "Whites" to AdjustKind.Whites,
            "Temperature" to AdjustKind.Temperature,
            "Tint" to AdjustKind.Tint,
            "Saturation" to AdjustKind.Saturation,
        )

        expected.forEach { (name, kind) -> assertEquals(name, kind, MONET_OPERATIONS[name]) }
    }

    /**
     * §3: the HSL half is an exact match — the same eight bands and three channels adjust_hsl.md
     * §10 gave `AdjustKind`. Which is worth an assertion rather than an assumption: it means
     * T54's 24 entries were the right 24.
     */
    @Test
    fun `all 24 of MonetGPT's colour adjustments map, one per HSL kind`() {
        val bands = listOf(
            "Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta",
        )
        val channels = listOf("Hue", "Saturation", "Luminance")

        val mapped = channels.flatMap { channel ->
            bands.map { band ->
                val name = "${channel}Adjustment$band"
                assertTrue("$name is missing", MONET_OPERATIONS.containsKey(name))
                MONET_OPERATIONS.getValue(name)
            }
        }

        assertEquals(HSL_KINDS, mapped.size)
        assertEquals(HSL_KINDS, mapped.toSet().size)
        assertEquals(AdjustKind.entries.count { it.hsl != null }, mapped.toSet().size)
    }

    @Test
    fun `the table names nothing that is not an operation MonetGPT sends`() {
        assertEquals(TONE_OPERATIONS + HSL_KINDS, MONET_OPERATIONS.size)
    }

    /** §3: "All adjustment values are scaled between -100 and +100" is the whole conversion. */
    @Test
    fun `the value scale is a hundred`() {
        assertEquals(100f, MONET_VALUE_SCALE, 0f)
    }

    private companion object {
        const val TONE_OPERATIONS = 9
        const val HSL_KINDS = 24
    }
}
