package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.ai.Margins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/outpaint.md §5, §8. */
@RunWith(RobolectricTestRunner::class)
class WhitePadTest {

    @Test
    fun `the new border is exactly opaque white`() {
        val padded = WhitePad.apply(image(), MARGINS)
        val interior = WhitePad.interiorOf(SIZE, SIZE, MARGINS)

        for (y in 0 until padded.height) {
            for (x in 0 until padded.width) {
                if (!interior.contains(x, y)) {
                    assertEquals("($x, $y)", OPAQUE_WHITE, padded.getPixel(x, y))
                }
            }
        }
    }

    @Test
    fun `the interior equals the input pixel for pixel`() {
        val image = image()

        val padded = WhitePad.apply(image, MARGINS)

        val interior = WhitePad.interiorOf(SIZE, SIZE, MARGINS)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                assertEquals(
                    "($x, $y)",
                    image.getPixel(x, y),
                    padded.getPixel(interior.left + x, interior.top + y),
                )
            }
        }
    }

    @Test
    fun `the canvas is the sum of its parts, so the interior always fits`() {
        val margins = Margins(0.1f, 0.33f, 0.1f, 0.07f)

        val padded = WhitePad.apply(image(), margins)

        // 16px a side: each margin rounds on its own, so 2 + 16 + 2 and 5 + 16 + 1.
        val interior = WhitePad.interiorOf(SIZE, SIZE, margins)
        assertEquals(20, padded.width)
        assertEquals(22, padded.height)
        assertEquals(2, interior.left)
        assertEquals(5, interior.top)
        assertEquals(SIZE, interior.width())
        assertEquals(SIZE, interior.height())
    }

    @Test
    fun `the input bitmap is not mutated`() {
        val image = image()
        val before = IntArray(SIZE * SIZE).also { image.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }

        WhitePad.apply(image, MARGINS)

        val after = IntArray(SIZE * SIZE).also { image.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        assertEquals(before.toList(), after.toList())
    }

    @Test
    fun `zero margins return a pixel-identical copy`() {
        val image = image()

        val padded = WhitePad.apply(image, Margins(0f, 0f, 0f, 0f))

        assertEquals(SIZE, padded.width)
        assertEquals(SIZE, padded.height)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                assertEquals("($x, $y)", image.getPixel(x, y), padded.getPixel(x, y))
            }
        }
    }

    @Test
    fun `the result is ARGB_8888`() {
        assertEquals(Bitmap.Config.ARGB_8888, WhitePad.apply(image(), MARGINS).config)
    }

    /** A margin that shrank the canvas would silently crop the photograph. */
    @Test
    fun `a negative margin fails loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            WhitePad.apply(image(), Margins(-0.1f, 0f, 0f, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WhitePad.apply(image(), Margins(0f, 0f, 0f, -0.1f))
        }
    }

    /** A gradient, so "copied verbatim" means more than "one colour survived". */
    private fun image(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, Color.argb(255, x * 8, y * 8, 40))
            }
        }
        return bitmap
    }

    private companion object {
        const val SIZE = 16
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
        val MARGINS = Margins(0.25f, 0.5f, 0.25f, 0f)
    }
}
