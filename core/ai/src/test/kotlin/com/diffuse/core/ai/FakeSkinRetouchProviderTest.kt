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

/**
 * specs/skin_retouch_pipeline.md §2, specs/skin_retouch_validation.md §4 (Provider).
 *
 * The fake is what every editor test will retouch through, so the contract the real provider owes
 * — one kind per call, an untouched input, a candidate that is already region-limited, a binary
 * support at input size, `NoChange` as a success — is asserted here rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
class FakeSkinRetouchProviderTest {

    private val provider = FakeSkinRetouchProvider()

    @Test
    fun `pixels outside the allowed region are left exactly as they were`() = runTest {
        val roi = skinRoi()
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val prepared = provider.prepare(roi, allowed, SkinRetouchKind.Blemish).valueOrFail()

        forEachPixel { x, y ->
            if (MaskBitmaps.alphaAt(allowed, x, y) == MaskBitmaps.CLEAR) {
                assertEquals(roi.getPixel(x, y), prepared.candidate.getPixel(x, y))
            }
        }
    }

    @Test
    fun `the input roi and allowed mask are not modified`() = runTest {
        val roi = skinRoi()
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)
        val roiBefore = roi.copy(Bitmap.Config.ARGB_8888, false)
        val allowedCoverage = MaskBitmaps.coverage(allowed)

        provider.prepare(roi, allowed, SkinRetouchKind.Blemish).valueOrFail()

        forEachPixel { x, y -> assertEquals(roiBefore.getPixel(x, y), roi.getPixel(x, y)) }
        assertEquals(allowedCoverage, MaskBitmaps.coverage(allowed), 0f)
    }

    @Test
    fun `the support marks every changed pixel and nothing else`() = runTest {
        val roi = skinRoi()
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val prepared = provider.prepare(roi, allowed, SkinRetouchKind.Blemish).valueOrFail()

        forEachPixel { x, y ->
            val changed = roi.getPixel(x, y) != prepared.candidate.getPixel(x, y)
            val supported = MaskBitmaps.alphaAt(prepared.changeSupport, x, y) == MaskBitmaps.OPAQUE
            assertEquals("($x, $y)", changed, supported)
        }
    }

    @Test
    fun `the support is binary and the size of the input`() = runTest {
        val prepared = provider
            .prepare(skinRoi(), MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS), SkinRetouchKind.Shine)
            .valueOrFail()

        assertEquals(SIZE, prepared.changeSupport.width)
        assertEquals(SIZE, prepared.changeSupport.height)
        assertEquals(SIZE, prepared.candidate.width)
        forEachPixel { x, y ->
            val alpha = MaskBitmaps.alphaAt(prepared.changeSupport, x, y)
            assertTrue("$alpha", alpha == MaskBitmaps.CLEAR || alpha == MaskBitmaps.OPAQUE)
        }
    }

    /** specs/skin_retouch_pipeline.md §2: nothing to correct is a success, not a failure. */
    @Test
    fun `an empty allowed region is a no-change outcome`() = runTest {
        val roi = skinRoi()

        val prepared = provider
            .prepare(roi, MaskBitmaps.empty(SIZE, SIZE), SkinRetouchKind.Blemish)
            .valueOrFail()

        assertEquals(CorrectionOutcome.NoChange, prepared.outcome)
        assertEquals(0f, MaskBitmaps.coverage(prepared.changeSupport), 0f)
        forEachPixel { x, y -> assertEquals(roi.getPixel(x, y), prepared.candidate.getPixel(x, y)) }
    }

    @Test
    fun `a prepared correction reports the outcome and the engine that produced it`() = runTest {
        val prepared = provider
            .prepare(skinRoi(), MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS), SkinRetouchKind.Blemish)
            .valueOrFail()

        assertEquals(CorrectionOutcome.Corrected, prepared.outcome)
        assertTrue(prepared.engineVersion.isNotBlank())
    }

    /** One request is one kind: two kinds off the same base must not be the same picture. */
    @Test
    fun `two kinds prepared from the same base differ`() = runTest {
        val roi = skinRoi()
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        val blemish = provider.prepare(roi, allowed, SkinRetouchKind.Blemish).valueOrFail()
        val shine = provider.prepare(roi, allowed, SkinRetouchKind.Shine).valueOrFail()

        assertNotEquals(
            blemish.candidate.getPixel(SIZE / 2, SIZE / 2),
            shine.candidate.getPixel(SIZE / 2, SIZE / 2),
        )
    }

    @Test
    fun `an unsupported kind fails rather than returning the input`() = runTest {
        val partial = FakeSkinRetouchProvider(supportedKinds = setOf(SkinRetouchKind.Blemish))

        val result = partial.prepare(
            skinRoi(),
            MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS),
            SkinRetouchKind.ShavingShadow,
        )

        assertEquals(Result.Failure(AppError.Unsupported), result)
    }

    @Test
    fun `a mask of the wrong size is rejected`() = runTest {
        val result = provider.prepare(skinRoi(), MaskBitmaps.empty(SIZE / 2, SIZE), SkinRetouchKind.Blemish)

        assertTrue("$result", result is Result.Failure && result.error is AppError.Invalid)
    }

    @Test
    fun `failNext fails only the next call`() = runTest {
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)
        provider.failNext(AppError.Unavailable)

        val failed = provider.prepare(skinRoi(), allowed, SkinRetouchKind.Blemish)
        val recovered = provider.prepare(skinRoi(), allowed, SkinRetouchKind.Blemish)

        assertEquals(Result.Failure(AppError.Unavailable), failed)
        assertTrue("$recovered", recovered is Result.Success)
    }

    @Test
    fun `the fake runs on the device and counts what it was asked for`() = runTest {
        val allowed = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, RADIUS)

        provider.prepare(skinRoi(), allowed, SkinRetouchKind.DarkCircles).valueOrFail()

        assertEquals(ExecutionLocation.Local, provider.executionLocation)
        assertEquals(1, provider.prepareCount)
        assertEquals(listOf(SkinRetouchKind.DarkCircles), provider.preparedKinds)
    }

    /** A face-toned ROI with a darker patch, so a change is visible and deterministic. */
    private fun skinRoi(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        forEachPixel { x, y ->
            bitmap.setPixel(x, y, if (x < SIZE / 2) SKIN else BLEMISH)
        }
        return bitmap
    }

    private inline fun forEachPixel(action: (x: Int, y: Int) -> Unit) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) action(x, y)
        }
    }

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> error("expected success, got $error")
    }

    private companion object {
        const val SIZE = 64
        const val RADIUS = 20f
        val SKIN = Color.rgb(222, 184, 160)
        val BLEMISH = Color.rgb(150, 90, 80)
    }
}
