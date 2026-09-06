package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/generative_erase.md §7, §12. */
@RunWith(RobolectricTestRunner::class)
class GeminiImageCodecTest {

    @Test
    fun `a 4096px image comes back at 1024 on the long edge, aspect preserved`() {
        val image = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)

        val scaled = GeminiImageCodec.downscale(image)

        assertEquals(1024, scaled.width)
        assertEquals(512, scaled.height)
    }

    @Test
    fun `a tall image is scaled by its own long edge`() {
        val image = Bitmap.createBitmap(1000, 4000, Bitmap.Config.ARGB_8888)

        val scaled = GeminiImageCodec.downscale(image)

        assertEquals(1024, scaled.height)
        assertEquals(256, scaled.width)
    }

    @Test
    fun `an image already inside the cap is handed back untouched`() {
        val image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)

        assertTrue(GeminiImageCodec.downscale(image) === image)
    }

    @Test
    fun `the mask is scaled nearest-neighbour and stays strictly binary`() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                mask.setPixel(x, y, if (x < 32) OPAQUE_ALPHA else 0)
            }
        }

        val scaled = GeminiImageCodec.downscaleMask(mask, 16, 16)

        assertEquals(16, scaled.width)
        assertEquals(16, scaled.height)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val alpha = scaled.getPixel(x, y) ushr ALPHA_SHIFT
                assertTrue("($x, $y) was $alpha", alpha == 0 || alpha == 255)
            }
        }
    }

    @Test
    fun `a mask already at the target size is handed back untouched`() {
        val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ALPHA_8)

        assertTrue(GeminiImageCodec.downscaleMask(mask, 32, 32) === mask)
    }

    @Test
    fun `an encoded image is a JPEG inside the upload cap`() {
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)

        val bytes = GeminiImageCodec.encode(image)

        assertNotNull(bytes)
        assertTrue(bytes!!.size <= GeminiImageCodec.MAX_UPLOAD_BYTES)
        // SOI marker: the provider promises `image/jpeg` on the wire.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF shl 24
        const val ALPHA_SHIFT = 24
    }
}
