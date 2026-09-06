package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/segmentation.md §3. */
@RunWith(RobolectricTestRunner::class)
class Sam3ImageCodecTest {

    @Test
    fun `an image inside the limit keeps its size`() {
        val encoded = Sam3ImageCodec.encode(bitmap(800, 600))

        assertNotNull(encoded)
        assertEquals(800, encoded!!.width)
        assertEquals(600, encoded.height)
    }

    @Test
    fun `a long edge over the limit is downscaled, preserving aspect`() {
        val encoded = Sam3ImageCodec.encode(bitmap(4000, 3000))!!

        assertEquals(Sam3ImageCodec.MAX_LONG_EDGE, encoded.width)
        assertEquals(1536, encoded.height)
    }

    @Test
    fun `a portrait image is bounded on its long edge too`() {
        val encoded = Sam3ImageCodec.encode(bitmap(3000, 4000))!!

        assertEquals(Sam3ImageCodec.MAX_LONG_EDGE, encoded.height)
        assertEquals(1536, encoded.width)
    }

    @Test
    fun `the encoded upload stays inside the server cap`() {
        val encoded = Sam3ImageCodec.encode(bitmap(4000, 3000))!!

        assertTrue(encoded.bytes.size <= Sam3ImageCodec.MAX_UPLOAD_BYTES)
        assertTrue(encoded.bytes.isNotEmpty())
    }

    private fun bitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
