package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.HslChannel
import com.diffuse.core.imaging.model.HslColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * specs/adjust_hsl.md §4 and §10. The band strip is built in code rather than committed as a
 * fixture: `fixtures/` is human-committed (specs/testing.md §7), and the 8-bit values of these
 * eight swatches decode back to their band centres exactly, which is what makes "the other seven
 * did not move" a fact rather than a tolerance.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HslOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val grey = HslBand.entries.size

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    /** Eight band centres at S 0.8 / L 0.5, then a neutral grey. */
    private fun bandStrip(): Bitmap {
        val bitmap = Bitmap.createBitmap(HslBand.entries.size + 1, 1, Bitmap.Config.ARGB_8888)
        HslBand.entries.forEach { band ->
            bitmap.setPixel(band.ordinal, 0, OPAQUE or HslColor.toRgb(band.centerDeg, 0.8f, 0.5f))
        }
        bitmap.setPixel(grey, 0, OPAQUE or 0x808080)
        return bitmap
    }

    private fun kindOf(band: HslBand, channel: HslChannel): AdjustKind =
        AdjustKind.entries.first { it.hsl?.band == band && it.hsl?.channel == channel }

    private fun channelDelta(a: Int, b: Int): Int =
        intArrayOf(16, 8, 0).maxOf { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) }

    private fun hueOf(argb: Int): Float {
        val hsl = FloatArray(3)
        HslColor.fromRgb(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            hsl,
        )
        return hsl[0]
    }

    private fun saturationOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    private fun golden(name: String, kind: AdjustKind, value: Float) {
        GoldenAssert.assertMatchesGolden(name, Ops.adjust(kind)(fixture(), value))
    }

    @Test
    fun `band weights sum to one for every hue`() {
        for (degrees in 0 until 360) {
            val sum = HslBand.entries
                .fold(0f) { acc, band -> acc + HslOps.bandWeight(band, degrees.toFloat()) }

            assertEquals("hue $degrees", 1f, sum, 1e-4f)
        }
    }

    @Test
    fun `a band carries no weight at any other band centre`() {
        HslBand.entries.forEach { band ->
            HslBand.entries.filter { it != band }.forEach { other ->
                assertEquals(
                    "${band.name} at ${other.name}'s centre",
                    0f,
                    HslOps.bandWeight(band, other.centerDeg),
                    0f,
                )
            }
        }
    }

    @Test
    fun `every hsl kind is the identity at zero`() {
        val input = fixture()

        AdjustKind.entries.filter { it.hsl != null }.forEach { kind ->
            assertTrue("$kind at 0 must return its input", Ops.adjust(kind)(input, 0f) === input)
        }
    }

    @Test
    fun `moving one band leaves every other band centre untouched`() {
        val input = bandStrip()

        AdjustKind.entries.mapNotNull { kind -> kind.hsl?.let { kind to it } }
            .forEach { (kind, target) ->
                listOf(-1f, 1f).forEach { value ->
                    val output = Ops.adjust(kind)(input, value)

                    HslBand.entries.filter { it != target.band }.forEach { other ->
                        val delta = channelDelta(
                            input.getPixel(other.ordinal, 0),
                            output.getPixel(other.ordinal, 0),
                        )
                        assertTrue(
                            "$kind at $value moved ${other.name} by $delta",
                            delta <= 1,
                        )
                    }
                }
            }
    }

    @Test
    fun `the neutral grey is untouched by every hsl kind`() {
        val input = bandStrip()

        AdjustKind.entries.filter { it.hsl != null }.forEach { kind ->
            listOf(-1f, 1f).forEach { value ->
                val delta = channelDelta(
                    input.getPixel(grey, 0),
                    Ops.adjust(kind)(input, value).getPixel(grey, 0),
                )

                assertTrue("$kind at $value moved the neutral grey by $delta", delta <= 1)
            }
        }
    }

    @Test
    fun `saturation at minus one greys only its own band`() {
        val input = bandStrip()

        val output = Ops.adjust(kindOf(HslBand.Green, HslChannel.Saturation))(input, -1f)

        assertTrue(
            "green should be grey, spread ${saturationOf(output.getPixel(HslBand.Green.ordinal, 0))}",
            saturationOf(output.getPixel(HslBand.Green.ordinal, 0)) <= 1,
        )
        assertTrue(
            "red should keep its colour",
            saturationOf(output.getPixel(HslBand.Red.ordinal, 0)) > 100,
        )
    }

    @Test
    fun `a hue shift wraps past three hundred and sixty`() {
        val input = bandStrip()

        val output = Ops.adjust(kindOf(HslBand.Magenta, HslChannel.Hue))(input, 1f)

        // 320° + 30° = 350°, not 350 − 360 and not a clamp at 360.
        assertEquals(350f, hueOf(output.getPixel(HslBand.Magenta.ordinal, 0)), 2f)
    }

    @Test
    fun `luminance brightens its own band and leaves the grey alone`() {
        val input = bandStrip()

        val output = Ops.adjust(kindOf(HslBand.Red, HslChannel.Luminance))(input, 0.5f)

        val before = input.getPixel(HslBand.Red.ordinal, 0)
        val after = output.getPixel(HslBand.Red.ordinal, 0)
        assertTrue(
            "red should brighten: ${before.toString(16)} → ${after.toString(16)}",
            (after and 0xFF) > (before and 0xFF),
        )
        assertEquals(
            "the grey has no hue to belong to a band",
            0,
            channelDelta(input.getPixel(grey, 0), output.getPixel(grey, 0)),
        )
    }

    @Test
    fun `red hue goldens`() {
        val kind = kindOf(HslBand.Red, HslChannel.Hue)
        golden("hsl_red_hue_+0.5", kind, 0.5f)
        golden("hsl_red_hue_-0.5", kind, -0.5f)
    }

    @Test
    fun `red saturation goldens`() {
        val kind = kindOf(HslBand.Red, HslChannel.Saturation)
        golden("hsl_red_saturation_+0.5", kind, 0.5f)
        golden("hsl_red_saturation_-0.5", kind, -0.5f)
    }

    @Test
    fun `red luminance goldens`() {
        val kind = kindOf(HslBand.Red, HslChannel.Luminance)
        golden("hsl_red_luminance_+0.5", kind, 0.5f)
        golden("hsl_red_luminance_-0.5", kind, -0.5f)
    }

    @Test
    fun `blue saturation goldens`() {
        val kind = kindOf(HslBand.Blue, HslChannel.Saturation)
        golden("hsl_blue_saturation_+0.5", kind, 0.5f)
        golden("hsl_blue_saturation_-0.5", kind, -0.5f)
    }
}

private const val OPAQUE = 0xFF000000.toInt()
