package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.model.AdjustKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LightOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun solid(color: Int, size: Int = 8): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun golden(name: String, kind: AdjustKind, value: Float) {
        GoldenAssert.assertMatchesGolden(name, Ops.adjust(kind)(fixture(), value))
    }

    @Test
    fun `exposure goldens`() {
        golden("exposure_+0.5", AdjustKind.Exposure, 0.5f)
        golden("exposure_-0.5", AdjustKind.Exposure, -0.5f)
    }

    @Test
    fun `contrast goldens`() {
        golden("contrast_+0.5", AdjustKind.Contrast, 0.5f)
        golden("contrast_-0.5", AdjustKind.Contrast, -0.5f)
    }

    @Test
    fun `highlights goldens`() {
        golden("highlights_+0.5", AdjustKind.Highlights, 0.5f)
        golden("highlights_-0.5", AdjustKind.Highlights, -0.5f)
    }

    @Test
    fun `shadows goldens`() {
        golden("shadows_+0.5", AdjustKind.Shadows, 0.5f)
        golden("shadows_-0.5", AdjustKind.Shadows, -0.5f)
    }

    @Test
    fun `every light kind is the identity at zero`() {
        val input = fixture()
        listOf(
            AdjustKind.Exposure,
            AdjustKind.Contrast,
            AdjustKind.Highlights,
            AdjustKind.Shadows,
        ).forEach { kind ->
            val output = Ops.adjust(kind)(input, 0f)
            assertTrue("$kind at 0 should return the input untouched", output === input)
        }
    }

    @Test
    fun `highlights barely touches a dark pixel`() {
        // specs/adjust_light.md: at luma 0.2 the highlight mask is zero, so +0.5 must move
        // the pixel by at most 1/255.
        val dark = solid(0xFF333333.toInt())

        val output = Ops.adjust(AdjustKind.Highlights)(dark, 0.5f)

        val before = dark.getPixel(0, 0) and 0xFF
        val after = output.getPixel(0, 0) and 0xFF
        assertTrue("moved from $before to $after", abs(before - after) <= 1)
    }

    @Test
    fun `shadows lifts a dark pixel and leaves a bright one`() {
        val dark = solid(0xFF1A1A1A.toInt())
        val bright = solid(0xFFF0F0F0.toInt())

        val liftedDark = Ops.adjust(AdjustKind.Shadows)(dark, 0.5f).getPixel(0, 0) and 0xFF
        val liftedBright = Ops.adjust(AdjustKind.Shadows)(bright, 0.5f).getPixel(0, 0) and 0xFF

        assertTrue("dark should lift", liftedDark > (dark.getPixel(0, 0) and 0xFF))
        assertEquals(bright.getPixel(0, 0) and 0xFF, liftedBright)
    }

    @Test
    fun `contrast pivots around mid grey`() {
        val mid = solid(0xFF808080.toInt())

        val output = Ops.adjust(AdjustKind.Contrast)(mid, 0.5f).getPixel(0, 0) and 0xFF

        assertTrue("mid grey should barely move, got $output", abs(output - 128) <= 1)
    }

    @Test
    fun `exposure plus one half brightens by roughly one stop`() {
        val grey = solid(0xFF404040.toInt())

        val output = Ops.adjust(AdjustKind.Exposure)(grey, 0.5f).getPixel(0, 0) and 0xFF

        // 0x40 = 64; 64 x 2^(0.5 x 2) = 128.
        assertTrue("expected ~128, got $output", abs(output - 128) <= 2)
    }
}
