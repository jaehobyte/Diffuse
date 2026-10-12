package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/multishot.md §5: RGB kept, `photoAlpha × feather(mask)`, a 2px feather applied once. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiShotSubjectTest {

    @Test
    fun `inside the mask the photo is kept, outside it is clear, and the edge ramps over 2px`() {
        val photo = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(ORANGE) }
        val mask = halfMask(SIZE, SIZE)

        val subject = MultiShotSubject.compose(photo, mask)

        assertEquals(255, Color.alpha(subject.getPixel(5, 10)))
        assertEquals(ORANGE, subject.getPixel(5, 10))
        assertEquals(0, Color.alpha(subject.getPixel(15, 10)))
        // The edge is between x 9 and 10: two pixels either side are in between, ramping down.
        val ramp = (8..11).map { Color.alpha(subject.getPixel(it, 10)) }
        assertTrue("ramp $ramp", ramp.zipWithNext().all { (a, b) -> a > b } && ramp.first() < 255 && ramp.last() > 0)
        assertEquals(255, Color.alpha(subject.getPixel(7, 10)))
        assertEquals(0, Color.alpha(subject.getPixel(12, 10)))
        // Colour is the photo's, not darkened towards the transparent side.
        assertEquals(Color.red(ORANGE), Color.red(subject.getPixel(10, 10)), 2)
    }

    @Test
    fun `the photo's own alpha is multiplied in, and a small mask is stretched`() {
        val photo = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(120, 10, 200, 30))
        }
        val mask = halfMask(SIZE / 2, SIZE / 2)

        val subject = MultiShotSubject.compose(photo, mask)

        assertEquals(SIZE, subject.width)
        assertEquals(120, Color.alpha(subject.getPixel(3, 3)))
        assertEquals(0, Color.alpha(subject.getPixel(17, 3)))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected, was $actual", kotlin.math.abs(expected - actual) <= tolerance)

    private fun halfMask(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
            for (y in 0 until height) for (x in 0 until width / 2) setPixel(x, y, Color.BLACK)
        }

    private companion object {
        const val SIZE = 20
        val ORANGE = Color.rgb(240, 120, 20)
    }
}
