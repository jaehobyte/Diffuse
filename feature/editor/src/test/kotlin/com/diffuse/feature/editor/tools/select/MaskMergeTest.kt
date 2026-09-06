package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/selection_tool.md §4, §10: the merge maths, with no UI in the way. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskMergeTest {

    @Test
    fun `add is the union of two overlapping circles`() {
        val left = circle(cx = 12f)
        val right = circle(cx = 20f)

        val merged = MaskOps.merged(left, right, MergeMode.Add)

        assertTrue(coverage(merged) > coverage(left))
        assertTrue(coverage(merged) < coverage(left) + coverage(right))
        assertBinary(merged)
    }

    @Test
    fun `subtract takes the overlap back out`() {
        val left = circle(cx = 12f)
        val right = circle(cx = 20f)
        val both = MaskOps.merged(left, right, MergeMode.Add)

        val back = MaskOps.merged(both, right, MergeMode.Subtract)

        assertEquals(coverage(left) - overlapCoverage(left, right), coverage(back), 0.001f)
        assertBinary(back)
    }

    @Test
    fun `adding to nothing is the incoming mask`() {
        val incoming = circle(cx = 12f)

        val merged = MaskOps.merged(null, incoming, MergeMode.Add)

        assertEquals(coverage(incoming), coverage(merged), 0f)
    }

    @Test
    fun `subtracting from nothing leaves nothing`() {
        val merged = MaskOps.merged(null, circle(cx = 12f), MergeMode.Subtract)

        assertEquals(0f, coverage(merged), 0f)
    }

    @Test
    fun `invert is its own inverse`() {
        val mask = circle(cx = 12f)

        val twice = MaskOps.inverted(MaskOps.inverted(mask))

        assertEquals(coverage(mask), coverage(twice), 0f)
        assertEquals(1f - coverage(mask), coverage(MaskOps.inverted(mask)), 0.001f)
    }

    @Test
    fun `a union of instances is one mask covering all of them`() {
        val masks = listOf(circle(cx = 8f), circle(cx = 24f))

        val union = MaskOps.union(masks)!!

        assertEquals(coverage(masks[0]) + coverage(masks[1]), coverage(union), 0.001f)
        assertBinary(union)
    }

    @Test
    fun `a union of nothing is null, not an empty bitmap`() {
        assertNull(MaskOps.union(emptyList()))
    }

    // ---- helpers ---------------------------------------------------------

    private fun circle(cx: Float, cy: Float = SIZE / 2f, r: Float = 6f): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = x - cx
                val dy = y - cy
                val set = dx * dx + dy * dy <= r * r
                bitmap.setPixel(x, y, if (set) MaskOps.OPAQUE shl 24 else 0)
            }
        }
        return bitmap
    }

    private fun coverage(mask: Bitmap): Float {
        var opaque = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) if (isSet(mask, x, y)) opaque++
        }
        return opaque.toFloat() / (mask.width * mask.height)
    }

    private fun overlapCoverage(a: Bitmap, b: Bitmap): Float {
        var both = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) if (isSet(a, x, y) && isSet(b, x, y)) both++
        }
        return both.toFloat() / (SIZE * SIZE)
    }

    private fun assertBinary(mask: Bitmap) {
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                val alpha = mask.getPixel(x, y) ushr 24
                assertTrue("pixel ($x, $y) is $alpha", alpha == 0 || alpha == MaskOps.OPAQUE)
            }
        }
    }

    private fun isSet(mask: Bitmap, x: Int, y: Int) = (mask.getPixel(x, y) ushr 24) != 0

    private companion object {
        const val SIZE = 32
    }
}
