package com.diffuse.core.imaging.model

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** specs/outpaint.md §3, §8. The arithmetic, before any pixels are involved. */
@RunWith(AndroidJUnit4::class)
class OutpaintGeometryTest {

    @Test
    fun `the expanded size is the sum of its parts, so the interior always fits`() {
        val margins = Margins(left = 0.1f, top = 0.33f, right = 0.1f, bottom = 0.07f)

        val width = margins.expandedWidth(WIDTH)
        val height = margins.expandedHeight(HEIGHT)

        assertEquals(margins.padLeft(WIDTH) + WIDTH + (0.1f * WIDTH).toInt(), width)
        assertTrue("the interior must fit", margins.padLeft(WIDTH) + WIDTH <= width)
        assertTrue("the interior must fit", margins.padTop(HEIGHT) + HEIGHT <= height)
    }

    @Test
    fun `zero margins leave the size alone`() {
        assertEquals(WIDTH, Margins.None.expandedWidth(WIDTH))
        assertEquals(HEIGHT, Margins.None.expandedHeight(HEIGHT))
        assertTrue(Margins.None.isEmpty)
    }

    @Test
    fun `a margin clamps at half the dimension`() {
        val clamped = Margins(left = 0.9f, top = -0.2f, right = MAX_MARGIN_FRACTION, bottom = 0f)
            .clamped()

        assertEquals(MAX_MARGIN_FRACTION, clamped.left, 0f)
        assertEquals(0f, clamped.top, 0f)
        assertEquals(MAX_MARGIN_FRACTION, clamped.right, 0f)
        assertFalse(clamped.isEmpty)
    }

    @Test
    fun `re-normalizing a crop with no margins is the identity`() {
        val rect = RectF(0.2f, 0.3f, 0.8f, 0.9f)

        val moved = Margins.renormalize(rect, from = Margins.None, to = Margins.None)

        assertRect(rect, moved)
    }

    @Test
    fun `a centred rect comes back where it started after a round trip`() {
        val rect = RectF(0.25f, 0.25f, 0.75f, 0.75f)
        val margins = Margins(0.25f, 0.25f, 0.25f, 0.25f)

        val expanded = Margins.renormalize(rect, from = Margins.None, to = margins)
        val back = Margins.renormalize(expanded, from = margins, to = Margins.None)

        // The canvas grew to 1.5x, and the rect keeps the same pixels: (0.25 + 0.25) / 1.5.
        assertRect(RectF(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f), expanded)
        assertRect(rect, back)
    }

    @Test
    fun `a one-sided margin moves the rect only on that axis`() {
        val rect = RectF(0f, 0f, 1f, 1f)

        val moved = Margins.renormalize(rect, from = Margins.None, to = Margins(left = 0.5f))

        assertRect(RectF(1f / 3f, 0f, 1f, 1f), moved)
    }

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val TOLERANCE = 1e-4f
    }
}
