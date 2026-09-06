package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.feature.editor.tools.select.MaskOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/generative_erase.md §4, T50: a margin, not a feather. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EraseMaskTest {

    @Test
    fun `dilation is a superset of the input`() {
        val mask = square(SIZE, from = 20, to = 40)

        val grown = MaskOps.dilated(mask, RADIUS)

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (isSet(mask, x, y)) {
                    assertTrue("($x, $y) was set and must stay set", isSet(grown, x, y))
                }
            }
        }
    }

    @Test
    fun `it grows on every side by the radius, and no further`() {
        val mask = square(SIZE, from = 20, to = 40)

        val grown = MaskOps.dilated(mask, RADIUS)

        assertTrue(isSet(grown, 20 - RADIUS, 30))
        assertTrue(isSet(grown, 40 + RADIUS, 30))
        assertTrue(isSet(grown, 30, 20 - RADIUS))
        assertTrue(isSet(grown, 30, 40 + RADIUS))
        assertFalse(isSet(grown, 20 - RADIUS - 1, 30))
        assertFalse(isSet(grown, 30, 40 + RADIUS + 1))
    }

    @Test
    fun `a one pixel mask grows on both sides`() {
        val mask = square(SIZE, from = 30, to = 30)

        val grown = MaskOps.dilated(mask, radiusPx = 2)

        assertTrue(isSet(grown, 28, 30))
        assertTrue(isSet(grown, 32, 30))
        assertTrue(isSet(grown, 30, 28))
        assertFalse(isSet(grown, 27, 30))
    }

    @Test
    fun `the result stays strictly binary`() {
        val grown = MaskOps.dilated(square(SIZE, from = 20, to = 40), RADIUS)

        for (y in 0 until SIZE step 3) {
            for (x in 0 until SIZE step 3) {
                val alpha = grown.getPixel(x, y) ushr ALPHA_SHIFT
                assertTrue("alpha $alpha at ($x, $y) is neither 0 nor 255", alpha == 0 || alpha == MaskOps.OPAQUE)
            }
        }
    }

    @Test
    fun `a zero radius copies rather than aliases`() {
        val mask = square(SIZE, from = 20, to = 40)

        val copy = MaskOps.dilated(mask, radiusPx = 0)

        assertTrue(copy !== mask)
        assertTrue(copy.sameAs(mask))
    }

    @Test
    fun `dilation is deterministic`() {
        val mask = square(SIZE, from = 20, to = 40)

        assertTrue(MaskOps.dilated(mask, RADIUS).sameAs(MaskOps.dilated(mask, RADIUS)))
    }

    @Test
    fun `the margin comes off the short edge with a floor`() {
        assertEquals(MaskMargin(1000, 800), 12)
        // A small mask would otherwise round down to a margin that is not a margin.
        assertEquals(MaskMargin(64, 64), EraseMask.MARGIN_MIN_PX)
    }

    @Suppress("FunctionNaming")
    private fun MaskMargin(width: Int, height: Int): Int =
        EraseMask.marginPx(Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8))

    private fun square(size: Int, from: Int, to: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        for (y in from..to) {
            for (x in from..to) {
                bitmap.setPixel(x, y, MaskOps.OPAQUE shl ALPHA_SHIFT)
            }
        }
        return bitmap
    }

    private fun isSet(mask: Bitmap, x: Int, y: Int) = (mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0

    private companion object {
        const val SIZE = 64
        const val RADIUS = 3
        const val ALPHA_SHIFT = 24
    }
}
