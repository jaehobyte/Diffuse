package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/generative_erase.md §4, §8. */
@RunWith(RobolectricTestRunner::class)
class FakeEraseProviderTest {

    private val provider = FakeEraseProvider()

    @Test
    fun `pixels outside the mask are left exactly as they were`() = runTest {
        val image = twoToneImage()
        val mask = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val out = provider.erase(image, mask, hint = null).valueOrFail()

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (MaskBitmaps.alphaAt(mask, x, y) == MaskBitmaps.CLEAR) {
                    assertEquals(
                        "pixel ($x, $y) outside the mask changed",
                        image.getPixel(x, y),
                        out.getPixel(x, y),
                    )
                }
            }
        }
    }

    @Test
    fun `the masked region is replaced`() = runTest {
        val image = twoToneImage()
        val mask = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val out = provider.erase(image, mask, hint = "사람").valueOrFail()

        val centre = out.getPixel(SIZE / 2, SIZE / 2)
        assertNotEquals(image.getPixel(SIZE / 2, SIZE / 2), centre)
        assertEquals(1, provider.eraseCount)
    }

    @Test
    fun `the same input twice gives identical output`() = runTest {
        val image = twoToneImage()
        val mask = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val first = provider.erase(image, mask, null).valueOrFail()
        val second = provider.erase(image, mask, null).valueOrFail()

        assertTrue(first.sameAs(second))
    }

    @Test
    fun `failNext propagates`() = runTest {
        val image = twoToneImage()
        val mask = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)
        provider.failNext(AppError.Unavailable)

        assertEquals(Result.Failure(AppError.Unavailable), provider.erase(image, mask, null))
    }

    /** Left half red, right half blue, so a mean fill is visibly neither. */
    private fun twoToneImage(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, if (x < SIZE / 2) Color.RED else Color.BLUE)
            }
        }
        return bitmap
    }

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val SIZE = 64
        const val RADIUS = 16f
    }
}
