package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/skin_retouch_validation.md §4, `work/tasks.md` requirement 5.
 *
 * The half of the port that decides what the model sees. Every expectation here is also a
 * `scripts/retouch/test_detector.py` expectation: the two implementations are the same arithmetic
 * written twice, and requirement 5 is that they cannot drift.
 */
@RunWith(RobolectricTestRunner::class)
class DetectorPreprocessTest {

    @Test
    fun `a square roi is scaled with no padding at all`() {
        val transform = letterboxTransform(320, 320, SIZE)

        assertEquals(0, transform.padLeft)
        assertEquals(0, transform.padTop)
        assertEquals(0, transform.padRight)
        assertEquals(0, transform.padBottom)
        assertEquals(2f, transform.scale, 0f)
    }

    @Test
    fun `a wide roi is padded top and bottom only`() {
        val transform = letterboxTransform(640, 320, SIZE)

        assertEquals(0, transform.padLeft)
        assertEquals(0, transform.padRight)
        assertEquals(160, transform.padTop)
        assertEquals(160, transform.padBottom)
    }

    /**
     * The rule the manifest states and the Python reference repeats: floor to the top and left.
     * Rounding the other way moves every box half a pixel, in a direction that depends on the
     * ROI's parity — a bug that stays invisible until a photo happens to be an odd size.
     */
    @Test
    fun `an odd remainder goes to the bottom and the right`() {
        val transform = letterboxTransform(640, 319, SIZE)

        assertEquals(319, transform.scaledHeight)
        assertEquals(160, transform.padTop)
        assertEquals(161, transform.padBottom)
        assertEquals(SIZE, transform.padTop + transform.scaledHeight + transform.padBottom)
    }

    @Test
    fun `a tall roi is padded left and right`() {
        val transform = letterboxTransform(300, 600, SIZE)

        assertEquals(640, transform.scaledHeight)
        assertEquals(320, transform.scaledWidth)
        assertEquals(160, transform.padLeft)
        assertEquals(160, transform.padRight)
    }

    /**
     * specs/skin_retouch_pipeline.md §3 puts the smallest offered face at 200px. Refusing to
     * upscale would hand the model a 640px canvas that is 90% grey.
     */
    @Test
    fun `a small roi is scaled up rather than floated in grey`() {
        val transform = letterboxTransform(200, 200, SIZE)

        assertEquals(3.2f, transform.scale, 1e-6f)
        assertEquals(640, transform.scaledWidth)
        assertEquals(640, transform.scaledHeight)
    }

    @Test
    fun `an empty roi is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { letterboxTransform(0, 100, SIZE) }
    }

    @Test
    fun `the tensor is rgb float nchw in zero to one`() {
        val roi = solid(8, 8, Color.rgb(255, 0, 0))

        val (tensor, _) = preprocess(roi, 8)

        assertEquals(3 * 8 * 8, tensor.size)
        // Red in channel 0, not channel 2: an ARGB word fed through raw is the mistake
        // requirement 5 names, and it would put red where the model expects blue.
        assertEquals(1f, tensor[0 * 64 + 4 * 8 + 4], 1e-6f)
        assertEquals(0f, tensor[2 * 64 + 4 * 8 + 4], 1e-6f)
    }

    @Test
    fun `padding uses the fixed grey and the image is placed inside it`() {
        val roi = solid(8, 4, Color.rgb(10, 20, 30))

        val (tensor, transform) = preprocess(roi, 8)

        val grey = LETTERBOX_PAD_VALUE / 255f
        assertEquals(grey, tensor[0], 1e-6f)
        assertEquals(10 / 255f, tensor[transform.padTop * 8], 1e-6f)
    }

    @Test
    fun `transparent pixels are composited over the fixed background`() {
        val roi = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        roi.setPixel(0, 0, Color.argb(0, 0, 0, 0))
        roi.setPixel(1, 0, Color.argb(255, 0, 0, 0))

        val (tensor, _) = preprocess(roi, 2)

        assertEquals(LETTERBOX_PAD_VALUE / 255f, tensor[0], 1e-6f)
        assertEquals(0f, tensor[1], 1e-6f)
    }

    @Test
    fun `the input bitmap is never modified`() {
        val roi = solid(16, 9, Color.rgb(120, 130, 140))
        val before = IntArray(16 * 9).also { roi.getPixels(it, 0, 16, 0, 0, 16, 9) }

        preprocess(roi, SIZE)

        val after = IntArray(16 * 9).also { roi.getPixels(it, 0, 16, 0, 0, 16, 9) }
        assertArrayEquals(before, after)
    }

    /**
     * Two source pixels to four destination ones: the interior samples land at 1/4 and 3/4 and the
     * edges clamp. Pinned in both languages because Android's canvas filter, OpenCV and this each
     * have their own convention (requirement 5).
     */
    @Test
    fun `bilinear resampling uses half pixel centres`() {
        val roi = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        roi.setPixel(0, 0, Color.rgb(0, 0, 0))
        roi.setPixel(1, 0, Color.rgb(100, 100, 100))

        val (tensor, transform) = preprocess(roi, 4)

        val row = transform.padTop * 4
        val values = (0 until 4).map { tensor[row + it] * 255f }
        assertEquals(listOf(0f, 25f, 75f, 100f), values.map { kotlin.math.round(it * 1000f) / 1000f })
    }

    @Test
    fun `the buffer may be reused between runs`() {
        val buffer = FloatArray(3 * SIZE * SIZE)
        val red = solid(4, 4, Color.rgb(255, 0, 0))
        val blue = solid(4, 4, Color.rgb(0, 0, 255))

        preprocess(red, SIZE, buffer)
        val afterRed = buffer.copyOf()
        preprocess(blue, SIZE, buffer)

        // Not a leftover from the first run: filling the buffer is part of preprocessing, so a
        // second, differently shaped ROI cannot inherit the first one's pixels.
        assertNotEquals(afterRed.toList(), buffer.toList())
        assertEquals(1f, buffer[2 * SIZE * SIZE + (SIZE / 2) * SIZE + SIZE / 2], 1e-6f)
    }

    @Test
    fun `a buffer of the wrong size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            preprocess(solid(4, 4, Color.WHITE), SIZE, FloatArray(10))
        }
    }

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private companion object {
        const val SIZE = 640
    }
}
