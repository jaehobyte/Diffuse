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
class DetailOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun flatGrey(size: Int = 64): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF808080.toInt()) }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r + g + b) / 3
    }

    @Test
    fun `sharpen golden`() {
        GoldenAssert.assertMatchesGolden(
            "sharpen_0.5",
            Ops.adjust(AdjustKind.Sharpen)(fixture(), 0.5f),
        )
    }

    @Test
    fun `vignette golden`() {
        GoldenAssert.assertMatchesGolden(
            "vignette_0.5",
            Ops.adjust(AdjustKind.Vignette)(fixture(), 0.5f),
        )
    }

    @Test
    fun `both detail kinds are the identity at zero`() {
        val input = fixture()

        assertTrue(Ops.adjust(AdjustKind.Sharpen)(input, 0f) === input)
        assertTrue(Ops.adjust(AdjustKind.Vignette)(input, 0f) === input)
    }

    @Test
    fun `sharpening a flat image changes nothing`() {
        // specs/adjust_detail.md: no ringing on flat areas.
        val flat = flatGrey()

        val output = Ops.adjust(AdjustKind.Sharpen)(flat, 1f)

        val before = flat.getPixel(32, 32)
        val after = output.getPixel(32, 32)
        assertTrue("flat grey moved from $before to $after", abs(luma(before) - luma(after)) <= 1)
    }

    @Test
    fun `vignette darkens the corners and leaves the centre`() {
        val flat = flatGrey(size = 128)

        val output = Ops.adjust(AdjustKind.Vignette)(flat, 1f)

        val centre = luma(output.getPixel(64, 64))
        val corner = luma(output.getPixel(0, 0))
        assertTrue("centre must be untouched, got $centre", abs(centre - 128) <= 1)
        assertTrue("corner $corner should be darker than centre $centre", corner < centre)
    }

    @Test
    fun `sharpen raises local contrast at an edge`() {
        val edge = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                edge.setPixel(x, y, if (x < 16) 0xFF404040.toInt() else 0xFFC0C0C0.toInt())
            }
        }

        val output = Ops.adjust(AdjustKind.Sharpen)(edge, 1f)

        val darkSide = luma(output.getPixel(15, 16))
        val brightSide = luma(output.getPixel(16, 16))
        assertTrue(
            "edge contrast should grow: $darkSide vs $brightSide",
            (brightSide - darkSide) > (0xC0 - 0x40),
        )
    }
}
