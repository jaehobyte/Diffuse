package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/skin_retouch_pipeline.md §5: `R = clamp(B + sum_k(sk * Pk * (Ck - B)), 0, 1)`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinRetouchCompositeTest {

    @Test
    fun `all strengths zero returns a copy equal to the base`() {
        val base = base()
        val layers = listOf(
            SkinRetouchLayer(solid(Color.WHITE), leftHalf(), 0f),
            SkinRetouchLayer(solid(Color.BLACK), everywhere(), 0f),
        )

        val result = SkinRetouchComposite.composite(base, layers)

        assertNotSame(base, result)
        assertArrayEquals(base.pixels(), result.pixels())
        assertArrayEquals(IntArray(SIZE * SIZE), SkinRetouchComposite.support(base, layers).alphas())
    }

    @Test
    fun `one kind moves its support by its strength and nothing outside it`() {
        val base = base()
        val candidate = solid(Color.rgb(200, 100, 0))

        val result = SkinRetouchComposite.composite(base, listOf(SkinRetouchLayer(candidate, leftHalf(), 0.25f)))

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val b = base.getPixel(x, y)
                val r = result.getPixel(x, y)
                if (x >= SIZE / 2) {
                    assertEquals("($x, $y) outside the support changed", b, r)
                } else {
                    assertEquals(round(Color.red(b) + 0.25f * (200 - Color.red(b))), Color.red(r))
                    assertEquals(round(Color.green(b) + 0.25f * (100 - Color.green(b))), Color.green(r))
                    assertEquals(round(Color.blue(b) + 0.25f * (0 - Color.blue(b))), Color.blue(r))
                }
            }
        }
    }

    @Test
    fun `overlapping kinds sum their deltas and clamp once`() {
        val base = solid(Color.rgb(200, 50, 100))
        val brighter = solid(Color.rgb(255, 0, 100))
        val alsoBrighter = solid(Color.rgb(255, 0, 180))
        val layers = listOf(
            SkinRetouchLayer(brighter, everywhere(), 1f),
            SkinRetouchLayer(alsoBrighter, leftHalf(), 0.5f),
        )

        val result = SkinRetouchComposite.composite(base, layers)

        // Left: red 200 + 55 + 27.5 clamps to 255; green 50 - 50 - 25 clamps to 0; blue 100 + 40.
        assertEquals(Color.rgb(255, 0, 140), result.getPixel(1, 1))
        // Right: only the first kind.
        assertEquals(Color.rgb(255, 0, 100), result.getPixel(SIZE - 1, 1))
        assertArrayEquals(everywhere().alphas(), SkinRetouchComposite.support(base, layers).alphas())
    }

    @Test
    fun `a zero strength removes only its own delta`() {
        val base = base()
        val first = SkinRetouchLayer(solid(Color.rgb(10, 200, 30)), leftHalf(), 0.5f)
        val second = SkinRetouchLayer(solid(Color.rgb(250, 20, 220)), everywhere(), 0f)

        val both = SkinRetouchComposite.composite(base, listOf(first, second))
        val alone = SkinRetouchComposite.composite(base, listOf(first))

        assertArrayEquals(alone.pixels(), both.pixels())
        assertArrayEquals(leftHalf().alphas(), SkinRetouchComposite.support(base, listOf(first, second)).alphas())
    }

    @Test
    fun `alpha comes from the base and the support leaves out transparent pixels`() {
        val base = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                base.setPixel(x, y, if (y == 0) Color.TRANSPARENT else Color.rgb(40, 40, 40))
            }
        }
        val layers = listOf(SkinRetouchLayer(solid(Color.rgb(90, 90, 90)), everywhere(), 1f))

        val result = SkinRetouchComposite.composite(base, layers)
        val support = SkinRetouchComposite.support(base, layers)

        assertEquals(0, Color.alpha(result.getPixel(1, 0)))
        assertEquals(Color.rgb(90, 90, 90), result.getPixel(1, 1))
        assertEquals(0, support.getPixel(1, 0) ushr ALPHA_SHIFT)
        assertEquals(OPAQUE, support.getPixel(1, 1) ushr ALPHA_SHIFT)
    }

    @Test
    fun `the same inputs give the same pixels and are left untouched`() {
        val base = base()
        val candidate = solid(Color.rgb(123, 45, 67))
        val mask = leftHalf()
        val basePixels = base.pixels()
        val candidatePixels = candidate.pixels()
        val maskAlphas = mask.alphas()
        val layers = listOf(SkinRetouchLayer(candidate, mask, 0.37f))

        val first = SkinRetouchComposite.composite(base, layers)
        val second = SkinRetouchComposite.composite(base, layers)
        SkinRetouchComposite.support(base, layers)

        assertArrayEquals(first.pixels(), second.pixels())
        assertArrayEquals(basePixels, base.pixels())
        assertArrayEquals(candidatePixels, candidate.pixels())
        assertArrayEquals(maskAlphas, mask.alphas())
    }

    @Test
    fun `mismatched sizes or strengths are programmer errors`() {
        val base = base()

        assertThrows(IllegalArgumentException::class.java) {
            SkinRetouchComposite.composite(
                base,
                listOf(SkinRetouchLayer(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), leftHalf(), 1f)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinRetouchComposite.composite(base, listOf(SkinRetouchLayer(solid(Color.RED), leftHalf(), 1.5f)))
        }
    }

    @Test
    fun `an ROI result and support are placed on the full canvas at their offset`() {
        val canvas = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLUE) }
        val roi = Rect(3, 2, 3 + SIZE, 2 + SIZE)
        val roiResult = solid(Color.RED)

        val pasted = SkinRetouchComposite.pasteIntoCanvas(canvas, roi, roiResult)
        val support = SkinRetouchComposite.canvasSupport(CANVAS_WIDTH, CANVAS_HEIGHT, roi, leftHalf())

        assertEquals(Color.BLUE, canvas.getPixel(3, 2))
        assertEquals(Color.RED, pasted.getPixel(3, 2))
        assertEquals(Color.RED, pasted.getPixel(roi.right - 1, roi.bottom - 1))
        assertEquals(Color.BLUE, pasted.getPixel(2, 2))
        assertEquals(Color.BLUE, pasted.getPixel(roi.right, roi.bottom))
        assertEquals(Bitmap.Config.ALPHA_8, support.config)
        assertEquals(OPAQUE, support.getPixel(3, 2) ushr ALPHA_SHIFT)
        assertEquals(0, support.getPixel(3 + SIZE / 2, 2) ushr ALPHA_SHIFT)
        assertEquals(0, support.getPixel(2, 2) ushr ALPHA_SHIFT)
        assertThrows(IllegalArgumentException::class.java) {
            val outside = Rect(CANVAS_WIDTH - 1, 0, CANVAS_WIDTH - 1 + SIZE, SIZE)
            SkinRetouchComposite.pasteIntoCanvas(canvas, outside, roiResult)
        }
        assertTrue(pasted.isMutable)
    }

    // ---- fixtures --------------------------------------------------------

    private fun base(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) setPixel(x, y, Color.rgb(x * 30, y * 30, 128))
        }
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun leftHalf(): Bitmap = mask { x, _ -> x < SIZE / 2 }

    private fun everywhere(): Bitmap = mask { _, _ -> true }

    private fun mask(inside: (Int, Int) -> Boolean): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply {
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) setPixel(x, y, if (inside(x, y)) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }

    private fun round(value: Float): Int = Math.round(value.coerceIn(0f, 255f))

    private fun Bitmap.pixels(): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun Bitmap.alphas(): IntArray = pixels().map { it ushr ALPHA_SHIFT }.toIntArray()

    private companion object {
        const val SIZE = 8
        const val CANVAS_WIDTH = 16
        const val CANVAS_HEIGHT = 12
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
