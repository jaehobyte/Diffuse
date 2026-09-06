package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/selection_tool.md §10: the outline's bounds must match the mask's, ±1px. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskOutlineTest {

    @Test
    fun `the outline bounds match the mask bounds`() {
        val mask = rect(left = 4, top = 6, right = 20, bottom = 18)

        val bounds = MaskOutline.pathOf(mask).getBounds()

        assertEquals(4f, bounds.left, 1f)
        assertEquals(6f, bounds.top, 1f)
        assertEquals(20f, bounds.right, 1f)
        assertEquals(18f, bounds.bottom, 1f)
    }

    @Test
    fun `two separate regions both contribute to the outline`() {
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        fill(mask, 2, 2, 6, 6)
        fill(mask, 20, 20, 28, 28)

        val bounds = MaskOutline.pathOf(mask).getBounds()

        assertEquals(2f, bounds.left, 1f)
        assertEquals(28f, bounds.right, 1f)
    }

    @Test
    fun `an empty mask is reported empty and traces nothing`() {
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) for (x in 0 until SIZE) mask.setPixel(x, y, 0)

        assertTrue(MaskOutline.isEmpty(mask))
        assertTrue(MaskOutline.pathOf(mask).isEmpty)
    }

    @Test
    fun `a mask with any set pixel is not empty`() {
        assertFalse(MaskOutline.isEmpty(rect(1, 1, 2, 2)))
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        fill(bitmap, left, top, right, bottom)
        return bitmap
    }

    private fun fill(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val inside = x in left until right && y in top until bottom
                if (inside) bitmap.setPixel(x, y, MaskOps.OPAQUE shl 24)
            }
        }
    }

    private companion object {
        const val SIZE = 32
    }
}
