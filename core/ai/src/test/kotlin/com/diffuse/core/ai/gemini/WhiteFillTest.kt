package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/generative_erase.md §4. */
@RunWith(RobolectricTestRunner::class)
class WhiteFillTest {

    @Test
    fun `masked pixels become opaque white`() {
        val image = image()
        val mask = mask { x, _ -> x < HALF }

        val filled = WhiteFill.apply(image, mask)

        for (y in 0 until SIZE) {
            for (x in 0 until HALF) {
                assertEquals("($x, $y)", OPAQUE_WHITE, filled.getPixel(x, y))
            }
        }
    }

    @Test
    fun `unmasked pixels are copied verbatim`() {
        val image = image()
        val mask = mask { x, _ -> x < HALF }

        val filled = WhiteFill.apply(image, mask)

        for (y in 0 until SIZE) {
            for (x in HALF until SIZE) {
                assertEquals("($x, $y)", image.getPixel(x, y), filled.getPixel(x, y))
            }
        }
    }

    @Test
    fun `the input bitmap is not mutated`() {
        val image = image()
        val before = IntArray(SIZE * SIZE).also { image.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }

        WhiteFill.apply(image, mask { _, _ -> true })

        val after = IntArray(SIZE * SIZE).also { image.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        assertEquals(before.toList(), after.toList())
    }

    @Test
    fun `an all-clear mask yields a pixel-identical copy`() {
        val image = image()

        val filled = WhiteFill.apply(image, mask { _, _ -> false })

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                assertEquals("($x, $y)", image.getPixel(x, y), filled.getPixel(x, y))
            }
        }
    }

    @Test
    fun `the result is ARGB_8888 at the input size`() {
        val filled = WhiteFill.apply(image(), mask { _, _ -> false })

        assertEquals(Bitmap.Config.ARGB_8888, filled.config)
        assertEquals(SIZE, filled.width)
        assertEquals(SIZE, filled.height)
    }

    @Test
    fun `a mask of the wrong size fails loudly`() {
        val wrong = Bitmap.createBitmap(SIZE + 1, SIZE, Bitmap.Config.ALPHA_8)

        assertThrows(IllegalArgumentException::class.java) {
            WhiteFill.apply(image(), wrong)
        }
    }

    /** A gradient, so "copied verbatim" means something stronger than "one colour survived". */
    private fun image(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, Color.argb(255, x * 8, y * 8, 40))
            }
        }
        return bitmap
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
        const val SIZE = 16
        const val HALF = 8
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}
