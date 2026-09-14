package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/**
 * specs/skin_retouch_validation.md §4, `work/tasks.md` requirement 7.
 *
 * The candidate mask is a **pure** transform: boxes and an allowance in, a 0/255 mask out. What is
 * pinned here is the subset property the whole safety argument rests on — the result never leaves
 * the allowance and never touches a transparent pixel — plus the rasterisation rule, which has to
 * match `scripts/retouch/detector.py` exactly or a box moves by a pixel between the two.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateMaskTest {

    @Test
    fun `the candidate mask is the box interior and nothing more`() {
        val mask = candidateMask(listOf(detection(2f, 3f, 6f, 7f)), allowAll(10, 10))

        val bytes = mask.alphaBytes()
        assertEquals(16, bytes.count { it != 0.toByte() })
        assertTrue(bytes.all { it == 0.toByte() || it == 255.toByte() })
        assertTrue(bytes[3 * 10 + 2] != 0.toByte())
        assertEquals(0.toByte(), bytes[2 * 10 + 2])
    }

    @Test
    fun `the candidate mask never leaves the allowance`() {
        val allowed = Bitmap.createBitmap(10, 10, Bitmap.Config.ALPHA_8)
        allowed.writeAlpha { x, y -> if (x < 5 && y < 5) 255 else 0 }

        val mask = candidateMask(listOf(detection(0f, 0f, 10f, 10f)), allowed)

        assertEquals(25, mask.alphaBytes().count { it != 0.toByte() })
    }

    @Test
    fun `the candidate mask excludes transparent pixels`() {
        val roi = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                roi.setPixel(x, y, if (y < 3) Color.TRANSPARENT else Color.WHITE)
            }
        }

        val mask = candidateMask(listOf(detection(0f, 0f, 10f, 10f)), allowAll(10, 10), roi)

        assertEquals(70, mask.alphaBytes().count { it != 0.toByte() })
    }

    /** requirement 7: a missing allowance is never replaced with "all of the face". */
    @Test
    fun `an empty allowance gives an empty candidate mask`() {
        val allowed = Bitmap.createBitmap(10, 10, Bitmap.Config.ALPHA_8)
        allowed.writeAlpha { _, _ -> 0 }

        val mask = candidateMask(listOf(detection(0f, 0f, 10f, 10f)), allowed)

        assertTrue(mask.alphaBytes().all { it == 0.toByte() })
    }

    @Test
    fun `no detections gives an empty mask rather than the whole allowance`() {
        val mask = candidateMask(emptyList(), allowAll(10, 10))

        assertTrue(mask.alphaBytes().all { it == 0.toByte() })
    }

    @Test
    fun `neither the allowance nor the roi is modified`() {
        val allowed = allowAll(10, 10)
        val roi = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val allowedBefore = allowed.alphaBytes()
        val roiBefore = IntArray(100).also { roi.getPixels(it, 0, 10, 0, 0, 10, 10) }

        candidateMask(listOf(detection(1f, 1f, 5f, 5f)), allowed, roi)

        assertArrayEquals(allowedBefore, allowed.alphaBytes())
        assertArrayEquals(roiBefore, IntArray(100).also { roi.getPixels(it, 0, 10, 0, 0, 10, 10) })
    }

    @Test
    fun `a box outside the roi contributes nothing`() {
        val mask = candidateMask(listOf(detection(20f, 20f, 30f, 30f)), allowAll(10, 10))

        assertTrue(mask.alphaBytes().all { it == 0.toByte() })
    }

    /** Half-open on the right, so the column at x=5 belongs to the second box only. */
    @Test
    fun `adjacent boxes do not both claim the shared edge`() {
        val left = detection(0f, 0f, 5f, 10f)
        val right = detection(5f, 0f, 10f, 10f)

        val both = candidateMask(listOf(left, right), allowAll(10, 10))
        val onlyLeft = candidateMask(listOf(left), allowAll(10, 10))

        assertEquals(100, both.alphaBytes().count { it != 0.toByte() })
        assertEquals(50, onlyLeft.alphaBytes().count { it != 0.toByte() })
    }

    /**
     * `ALPHA_8` rows are padded to the bitmap's own stride, and a face ROI is any width at all.
     * Treating `width * height` as the buffer size throws outright on an unaligned width — which
     * is most of them — so this covers a realistically shaped ROI rather than a tidy one.
     */
    @Test
    fun `an odd width allowance round trips through the mask`() {
        val allowed = Bitmap.createBitmap(301, 173, Bitmap.Config.ALPHA_8)
        allowed.writeAlpha { x, _ -> if (x < 150) 255 else 0 }

        val mask = candidateMask(listOf(detection(0f, 0f, 301f, 173f)), allowed)

        assertEquals(301, mask.width)
        assertEquals(173, mask.height)
        val bytes = mask.alphaBytes()
        assertEquals(150 * 173, bytes.count { it != 0.toByte() })
        // Row-by-row, so a stride mistake shows up as a diagonal drift rather than a total count.
        for (y in 0 until 173) {
            assertEquals("row $y", 0.toByte(), bytes[y * 301 + 150])
            assertTrue("row $y", bytes[y * 301 + 149] != 0.toByte())
        }
    }

    /**
     * The same odd width, but with the allowance produced the way a real one would be: by
     * `extractAlpha()`, whose `ALPHA_8` result is padded to a **wider** stride than the bitmap.
     * `Bitmap.createBitmap(301, 173, ALPHA_8)` happens to come back with `rowBytes == width`, so
     * the round trip above passes even on code that ignores the stride entirely; this one does not.
     */
    @Test
    fun `a padded allowance from extractAlpha round trips through the mask`() {
        val source = Bitmap.createBitmap(301, 173, Bitmap.Config.ARGB_8888)
        for (y in 0 until 173) {
            for (x in 0 until 301) {
                source.setPixel(x, y, if (x < 150) Color.BLACK else Color.TRANSPARENT)
            }
        }
        val allowed = source.extractAlpha()
        assertEquals(Bitmap.Config.ALPHA_8, allowed.config)
        // Asserted rather than assumed: if a platform ever stopped padding this, the test would
        // still pass while covering nothing, and the bug it exists for would come back unseen.
        // Here it is 304 bytes per row for a 301-pixel one.
        assertTrue("extractAlpha gave an unpadded row; this no longer covers a stride", allowed.rowBytes > 301)

        // Empty detections first: requirement 7's "no detections is a normal empty mask", which
        // is the call that threw outright when the buffer was sized `width * height`.
        assertTrue(candidateMask(emptyList(), allowed).alphaBytes().all { it == 0.toByte() })

        val mask = candidateMask(listOf(detection(0f, 0f, 301f, 173f)), allowed, source)

        val bytes = mask.alphaBytes()
        assertEquals(150 * 173, bytes.count { it != 0.toByte() })
        for (y in 0 until 173) {
            assertEquals("row $y", 0.toByte(), bytes[y * 301 + 150])
            assertTrue("row $y", bytes[y * 301 + 149] != 0.toByte())
        }
    }

    @Test
    fun `an allowance of the wrong config is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            candidateMask(emptyList(), Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        }
    }

    @Test
    fun `a roi that is not the allowance size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            candidateMask(
                emptyList(),
                allowAll(10, 10),
                Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888),
            )
        }
    }

    private fun detection(left: Float, top: Float, right: Float, bottom: Float) =
        BlemishDetection(RectF(left, top, right, bottom), confidence = 0.9f, classId = 0)

    private fun allowAll(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply { writeAlpha { _, _ -> 255 } }

    /** Stride-aware, like the code under test: `ALPHA_8` rows are padded to [Bitmap.rowBytes]. */
    private fun Bitmap.writeAlpha(value: (Int, Int) -> Int) {
        val bytes = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) bytes[y * rowBytes + x] = value(x, y).toByte()
        }
        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    }

    /** Returns unpadded `width * height` order, whatever the bitmap's stride is. */
    private fun Bitmap.alphaBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(byteCount)
        copyPixelsToBuffer(buffer)
        val padded = buffer.array()
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            padded.copyInto(out, y * width, y * rowBytes, y * rowBytes + width)
        }
        return out
    }
}
