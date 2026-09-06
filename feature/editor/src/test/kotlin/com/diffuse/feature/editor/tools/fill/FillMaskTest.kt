package com.diffuse.feature.editor.tools.fill

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.feature.editor.tools.select.MaskOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** specs/generative_fill.md §2, T67. */
@RunWith(AndroidJUnit4::class)
class FillMaskTest {

    @Test
    fun `the box is the tightest rect around the set pixels`() {
        val mask = mask { x, y -> x in 10..19 && y in 20..29 }

        assertEquals(Rect(10, 20, 20, 30), FillMask.boundingBox(mask))
    }

    @Test
    fun `a mask with nothing set has no box`() {
        assertNull(FillMask.boundingBox(mask { _, _ -> false }))
        assertNull(FillMask.paddedBox(mask { _, _ -> false }))
        assertNull(FillMask.rectangle(mask { _, _ -> false }))
    }

    @Test
    fun `a single pixel is a box of one`() {
        assertEquals(Rect(7, 8, 8, 9), FillMask.boundingBox(mask { x, y -> x == 7 && y == 8 }))
    }

    /** A disjoint selection is one region to fill, so the box spans both parts. */
    @Test
    fun `two blobs give one box spanning both`() {
        val mask = mask { x, y -> (x in 5..7 && y in 5..7) || (x in 40..42 && y in 40..42) }

        assertEquals(Rect(5, 5, 43, 43), FillMask.boundingBox(mask))
    }

    // ---- the margin ------------------------------------------------------

    @Test
    fun `each side moves out by the margin fraction of the box`() {
        // A 20x10 box at (30, 30): 30% is 6 across and 3 down, on each side.
        val mask = mask { x, y -> x in 30..49 && y in 30..39 }

        assertEquals(Rect(24, 27, 56, 43), FillMask.paddedBox(mask))
    }

    @Test
    fun `the margin clamps at every edge rather than running off the bitmap`() {
        val mask = mask { x, y -> x in 0..9 && y in 0..9 }

        val padded = FillMask.paddedBox(mask)!!

        assertEquals(0, padded.left)
        assertEquals(0, padded.top)
        assertEquals(13, padded.right)
        assertEquals(13, padded.bottom)
    }

    @Test
    fun `a full-frame selection stays the full frame`() {
        val padded = FillMask.paddedBox(mask { _, _ -> true })!!

        assertEquals(Rect(0, 0, SIZE, SIZE), padded)
    }

    // ---- the rectangle ---------------------------------------------------

    /** The point of T67: what the model is shown is a rectangle, not the thing's outline. */
    @Test
    fun `the rectangle is filled solid, with nothing set outside it`() {
        val mask = mask { x, y -> (x - 30) * (x - 30) + (y - 30) * (y - 30) < 64 }
        val padded = FillMask.paddedBox(mask)!!

        val rectangle = FillMask.rectangle(mask)!!

        assertEquals(SIZE, rectangle.width)
        assertEquals(SIZE, rectangle.height)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                assertEquals("($x, $y)", padded.contains(x, y), MaskOps.isSet(rectangle, x, y))
            }
        }
    }

    /** Every pixel the user chose is inside what gets sent — the margin only ever adds. */
    @Test
    fun `the rectangle contains the whole selection`() {
        val mask = mask { x, y -> x in 12..38 && y in 44..46 }

        val rectangle = FillMask.rectangle(mask)!!

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (MaskOps.isSet(mask, x, y)) {
                    assertTrue("($x, $y) was selected", MaskOps.isSet(rectangle, x, y))
                }
            }
        }
    }

    private fun mask(set: (Int, Int) -> Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, if (set(x, y)) OPAQUE_ALPHA else 0)
            }
        }
        return bitmap
    }

    private companion object {
        const val SIZE = 64
        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}
