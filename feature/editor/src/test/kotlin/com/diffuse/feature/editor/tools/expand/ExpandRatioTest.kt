package com.diffuse.feature.editor.tools.expand

import com.diffuse.core.imaging.model.Margins
import org.junit.Assert.assertEquals
import org.junit.Test

/** specs/outpaint.md §6's readout: "비율 4:3 → 9:16". */
class ExpandRatioTest {

    @Test
    fun `the ratios a person actually asks for read as themselves`() {
        assertEquals("1:1", ratioText(1f))
        assertEquals("4:3", ratioText(4f / 3f))
        assertEquals("3:2", ratioText(3f / 2f))
        assertEquals("16:9", ratioText(16f / 9f))
        assertEquals("9:16", ratioText(9f / 16f))
        assertEquals("4:5", ratioText(4f / 5f))
    }

    /** A pixel count that reduces to nothing still has to read as something. */
    @Test
    fun `an awkward aspect gets the nearest small ratio, not its own pixel counts`() {
        assertEquals("4:3", ratioText(2251f / 1689f))
    }

    @Test
    fun `a degenerate aspect falls back rather than dividing by zero`() {
        assertEquals("1:1", ratioText(0f))
        assertEquals("1:1", ratioText(Float.NaN))
    }

    @Test
    fun `no margins leave the aspect alone`() {
        assertEquals(4f / 3f, expandedAspect(4f, 3f, Margins.None), TOLERANCE)
    }

    /** §1's motivating request: a 4:3 photo that has to become 9:16 for a story. */
    @Test
    fun `growing the top and bottom turns a landscape photo portrait`() {
        val margins = Margins(top = 0.5f, bottom = 0.5f)

        val aspect = expandedAspect(4f, 3f, margins)

        assertEquals(4f / 6f, aspect, TOLERANCE)
        assertEquals("2:3", ratioText(aspect))
    }

    @Test
    fun `growing one side moves only that axis`() {
        assertEquals(1.5f, expandedAspect(1f, 1f, Margins(right = 0.5f)), TOLERANCE)
        assertEquals(1f / 1.5f, expandedAspect(1f, 1f, Margins(bottom = 0.5f)), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
