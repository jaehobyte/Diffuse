package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.model.AdjustKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun golden(name: String, kind: AdjustKind, value: Float) {
        GoldenAssert.assertMatchesGolden(name, Ops.adjust(kind)(fixture(), value))
    }

    private fun saturationOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    @Test
    fun `temperature goldens`() {
        golden("temperature_+0.5", AdjustKind.Temperature, 0.5f)
        golden("temperature_-0.5", AdjustKind.Temperature, -0.5f)
    }

    @Test
    fun `tint goldens`() {
        golden("tint_+0.5", AdjustKind.Tint, 0.5f)
        golden("tint_-0.5", AdjustKind.Tint, -0.5f)
    }

    @Test
    fun `saturation goldens`() {
        golden("saturation_+0.5", AdjustKind.Saturation, 0.5f)
        golden("saturation_-0.5", AdjustKind.Saturation, -0.5f)
    }

    @Test
    fun `vibrance goldens`() {
        golden("vibrance_+0.5", AdjustKind.Vibrance, 0.5f)
        golden("vibrance_-0.5", AdjustKind.Vibrance, -0.5f)
    }

    @Test
    fun `every color kind is the identity at zero`() {
        val input = fixture()
        listOf(
            AdjustKind.Temperature,
            AdjustKind.Tint,
            AdjustKind.Saturation,
            AdjustKind.Vibrance,
        ).forEach { kind ->
            assertTrue("$kind at 0 must return its input", Ops.adjust(kind)(input, 0f) === input)
        }
    }

    @Test
    fun `saturation minus one turns every pixel grey`() {
        val output = Ops.adjust(AdjustKind.Saturation)(fixture(), -1f)

        val pixels = IntArray(output.width * output.height)
        output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        val coloured = pixels.count { saturationOf(it) > 1 }
        assertTrue("expected greyscale, $coloured pixels still have colour", coloured == 0)
    }

    @Test
    fun `vibrance moves a muted pixel more than an already saturated one`() {
        // specs/adjust_color.md: the weighting is (1 − existing saturation).
        val saturatedRed = solid(0xFFE60023.toInt())
        val skinTone = solid(0xFFE0AC69.toInt())

        val redDelta = abs(
            saturationOf(Ops.adjust(AdjustKind.Vibrance)(saturatedRed, 0.5f).getPixel(0, 0)) -
                saturationOf(saturatedRed.getPixel(0, 0)),
        )
        val skinDelta = abs(
            saturationOf(Ops.adjust(AdjustKind.Vibrance)(skinTone, 0.5f).getPixel(0, 0)) -
                saturationOf(skinTone.getPixel(0, 0)),
        )

        assertTrue(
            "saturated red moved $redDelta, muted skin moved $skinDelta",
            redDelta < skinDelta,
        )
    }

    @Test
    fun `temperature warms red and cools blue`() {
        val grey = solid(0xFF808080.toInt())

        val warm = Ops.adjust(AdjustKind.Temperature)(grey, 0.5f).getPixel(0, 0)

        assertTrue("red should rise", ((warm shr 16) and 0xFF) > 0x80)
        assertTrue("blue should fall", (warm and 0xFF) < 0x80)
        assertTrue("green should hold", abs(((warm shr 8) and 0xFF) - 0x80) <= 1)
    }
}
