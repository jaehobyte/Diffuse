package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** specs/crop.md §Tests. A 4:3 source, as the spec specifies. */
@RunWith(AndroidJUnit4::class)
class CropGeometryTest {

    private val canvasAspect = 4f / 3f
    private val full = RectF(0f, 0f, 1f, 1f)

    @Test
    fun `auto-shrink keeps the rect inside the rotated image`() {
        listOf(-45f, 15f, 45f).forEach { angle ->
            val shrunk = CropGeometry.shrinkToFit(full, angle, canvasAspect)

            assertTrue(
                "rect escaped the image at $angle: $shrunk",
                CropGeometry.contains(shrunk, angle, canvasAspect),
            )
            assertTrue("shrink should keep some area at $angle", shrunk.width() > 0.1f)
        }
    }

    @Test
    fun `auto-shrink preserves the aspect ratio`() {
        val start = RectF(0.1f, 0.2f, 0.9f, 0.8f)
        val before = start.width() / start.height()

        val shrunk = CropGeometry.shrinkToFit(start, 30f, canvasAspect)

        val after = shrunk.width() / shrunk.height()
        assertEquals(before, after, before * 0.005f)
    }

    @Test
    fun `an unrotated rect is left alone`() {
        val shrunk = CropGeometry.shrinkToFit(full, 0f, canvasAspect)

        assertEquals(full, shrunk)
    }

    @Test
    fun `presets hold their aspect within half a percent`() {
        listOf(
            AspectPreset.Square to 1f,
            AspectPreset.FourFive to 4f / 5f,
            AspectPreset.NineSixteen to 9f / 16f,
            AspectPreset.SixteenNine to 16f / 9f,
        ).forEach { (preset, expected) ->
            val rect = CropGeometry.applyPreset(full, preset, canvasAspect)

            // Normalised width x canvasAspect gives the on-screen width.
            val actual = (rect.width() * canvasAspect) / rect.height()
            assertTrue(
                "$preset gave $actual, expected $expected",
                abs(actual - expected) / expected <= 0.005f,
            )
            assertTrue("$preset escaped the canvas: $rect", rect.width() <= 1f + 1e-4f)
            assertTrue("$preset escaped the canvas: $rect", rect.height() <= 1f + 1e-4f)
        }
    }

    @Test
    fun `Free leaves the rect untouched`() {
        val start = RectF(0.2f, 0.1f, 0.7f, 0.6f)

        assertEquals(start, CropGeometry.applyPreset(start, AspectPreset.Free, canvasAspect))
    }

    @Test
    fun `a rect dragged below the minimum is clamped`() {
        val tiny = RectF(0.4f, 0.4f, 0.42f, 0.41f)

        val clamped = CropGeometry.clampToMinimum(tiny)

        assertEquals(CropGeometry.MIN_FRACTION, clamped.width(), 1e-4f)
        assertEquals(CropGeometry.MIN_FRACTION, clamped.height(), 1e-4f)
    }
}
