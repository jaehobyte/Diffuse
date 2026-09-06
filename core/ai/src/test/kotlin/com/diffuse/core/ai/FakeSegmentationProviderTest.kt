package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.PointF
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI

/** specs/ai_provider.md §6. The fake is what every UI test downstream depends on. */
@RunWith(RobolectricTestRunner::class)
class FakeSegmentationProviderTest {

    private val provider = FakeSegmentationProvider(openDelayMs = 0)
    private val image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    @Test
    fun `open reports the image size and tracks the session`() = runTest {
        val session = provider.open(image).valueOrFail()

        assertEquals(SIZE, session.imageWidth)
        assertEquals(SIZE, session.imageHeight)
        assertEquals(1, provider.openCount)
        assertEquals(listOf(session), provider.openSessions)
    }

    @Test
    fun `close releases the session`() = runTest {
        val session = provider.open(image).valueOrFail()

        provider.close(session)

        assertTrue(provider.openSessions.isEmpty())
    }

    @Test
    fun `byPoints returns a circle of the documented radius around the first foreground point`() =
        runTest {
            val session = provider.open(image).valueOrFail()

            val mask = provider.byPoints(session, fg(0.5f, 0.5f)).valueOrFail()

            // A radius of 0.2 x the short edge covers pi x 0.2^2 of a square image.
            val expected = (PI * 0.2 * 0.2).toFloat()
            assertEquals(expected, MaskBitmaps.coverage(mask.alpha), COVERAGE_TOLERANCE)
            assertEquals(MaskBitmaps.OPAQUE, MaskBitmaps.alphaAt(mask.alpha, SIZE / 2, SIZE / 2))
            assertEquals(MaskBitmaps.CLEAR, MaskBitmaps.alphaAt(mask.alpha, 0, 0))
        }

    @Test
    fun `a background point shrinks the mask`() = runTest {
        val session = provider.open(image).valueOrFail()
        val before = provider.byPoints(session, fg(0.5f, 0.5f)).valueOrFail()

        val prompt = PointPrompt(
            points = listOf(PointF(0.5f, 0.5f), PointF(0.55f, 0.5f)),
            labels = listOf(true, false),
        )
        val after = provider.byPoints(session, prompt).valueOrFail()

        assertTrue(
            "background point did not shrink the mask",
            MaskBitmaps.coverage(after.alpha) < MaskBitmaps.coverage(before.alpha),
        )
    }

    @Test
    fun `byText returns instances ordered by descending score`() = runTest {
        val session = provider.open(image).valueOrFail()

        val masks = provider.byText(session, "사람").valueOrFail()

        assertEquals(2, masks.size)
        assertTrue(masks[0].score > masks[1].score)
    }

    @Test
    fun `byText is deterministic for the same phrase and differs between phrases`() = runTest {
        val session = provider.open(image).valueOrFail()

        val first = provider.byText(session, "사람").valueOrFail().first()
        val again = provider.byText(session, "사람").valueOrFail().first()
        val other = provider.byText(session, "하늘").valueOrFail().first()

        assertTrue(first.alpha.sameAs(again.alpha))
        assertNotEquals(MaskBitmaps.coverage(first.alpha), MaskBitmaps.coverage(other.alpha))
    }

    @Test
    fun `an absent concept is an empty list, not a failure`() = runTest {
        val session = provider.open(image).valueOrFail()

        assertEquals(emptyList<SegMask>(), provider.byText(session, "없음").valueOrFail())
    }

    @Test
    fun `a blank phrase is rejected`() = runTest {
        val session = provider.open(image).valueOrFail()

        val result = provider.byText(session, "   ")

        assertTrue(result is Result.Failure && result.error is AppError.Invalid)
    }

    @Test
    fun `failNext applies to the next call only`() = runTest {
        val session = provider.open(image).valueOrFail()
        provider.failNext(AppError.Unavailable)

        val failed = provider.byPoints(session, fg(0.5f, 0.5f))
        val recovered = provider.byPoints(session, fg(0.5f, 0.5f))

        assertEquals(Result.Failure(AppError.Unavailable), failed)
        assertTrue(recovered is Result.Success)
    }

    @Test
    fun `a prompt with no foreground point yields an empty mask`() = runTest {
        val session = provider.open(image).valueOrFail()

        val prompt = PointPrompt(listOf(PointF(0.5f, 0.5f)), listOf(false))
        val mask = provider.byPoints(session, prompt).valueOrFail()

        assertEquals(0f, MaskBitmaps.coverage(mask.alpha), 0f)
    }

    private fun fg(x: Float, y: Float) = PointPrompt(listOf(PointF(x, y)), listOf(true))

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val SIZE = 120
        const val COVERAGE_TOLERANCE = 0.01f
    }
}
