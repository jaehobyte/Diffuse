package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

/**
 * T71 — specs/style_match.md §3.1 and specs/auto_enhance.md §3. The five tone kinds `AdjustKind`
 * was missing: the curve's two endpoints, its two shape controls, and midtone local contrast.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToneOpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- the goldens -----------------------------------------------------

    @Test
    fun `blacks goldens`() {
        golden("blacks_+0.5", AdjustKind.Blacks, 0.5f)
        golden("blacks_-0.5", AdjustKind.Blacks, -0.5f)
    }

    @Test
    fun `whites goldens`() {
        golden("whites_+0.5", AdjustKind.Whites, 0.5f)
        golden("whites_-0.5", AdjustKind.Whites, -0.5f)
    }

    @Test
    fun `fade goldens`() {
        golden("fade_+0.5", AdjustKind.Fade, 0.5f)
        golden("fade_-0.5", AdjustKind.Fade, -0.5f)
    }

    @Test
    fun `s curve goldens`() {
        golden("s_curve_+0.5", AdjustKind.SCurve, 0.5f)
        golden("s_curve_-0.5", AdjustKind.SCurve, -0.5f)
    }

    @Test
    fun `clarity goldens`() {
        golden("clarity_+0.5", AdjustKind.Clarity, 0.5f)
        golden("clarity_-0.5", AdjustKind.Clarity, -0.5f)
    }

    // ---- the properties every kind owes edit_model.md ---------------------

    /** edit_model.md's "neutral values are not stored" rule is only safe if 0 changes nothing. */
    @Test
    fun `every tone kind is the identity at zero`() {
        val input = fixture()

        TONE_KINDS.forEach { kind ->
            val output = Ops.adjust(kind)(input, 0f)
            assertEquals(kind.name, input.pixels(), output.pixels())
        }
    }

    @Test
    fun `every tone kind moves the photograph at half strength`() {
        val input = fixture()

        TONE_KINDS.forEach { kind ->
            val output = Ops.adjust(kind)(input, 0.5f)
            assertTrue("$kind did nothing", input.pixels() != output.pixels())
        }
    }

    /** Monotonic in its argument, so a slider never doubles back on the user. */
    @Test
    fun `each kind is monotonic in its value`() {
        val grey = solid(Color.rgb(120, 120, 120))

        TONE_KINDS.forEach { kind ->
            val darker = luminanceOf(Ops.adjust(kind)(grey, -0.6f))
            val neutral = luminanceOf(Ops.adjust(kind)(grey, 0f))
            val brighter = luminanceOf(Ops.adjust(kind)(grey, 0.6f))
            // Clarity and the s-curve leave a flat mid-grey alone by construction, so they are
            // allowed to be flat here — what is forbidden is turning back.
            assertTrue("$kind is not monotonic: $darker, $neutral, $brighter",
                darker <= neutral && neutral <= brighter ||
                    darker >= neutral && neutral >= brighter)
        }
    }

    // ---- what each one is actually for -----------------------------------

    /** The endpoints reach where their siblings do not: `Shadows` barely touches near-black. */
    @Test
    fun `blacks moves the deepest tone more than shadows does`() {
        val nearBlack = solid(Color.rgb(8, 8, 8))

        val byBlacks = luminanceOf(Ops.adjust(AdjustKind.Blacks)(nearBlack, 0.5f))
        val byShadows = luminanceOf(Ops.adjust(AdjustKind.Shadows)(nearBlack, 0.5f))

        assertTrue("$byBlacks should exceed $byShadows", byBlacks > byShadows)
    }

    @Test
    fun `whites moves the brightest tone more than highlights does`() {
        val nearWhite = solid(Color.rgb(247, 247, 247))

        val byWhites = luminanceOf(Ops.adjust(AdjustKind.Whites)(nearWhite, -0.5f))
        val byHighlights = luminanceOf(Ops.adjust(AdjustKind.Highlights)(nearWhite, -0.5f))

        assertTrue("$byWhites should be under $byHighlights", byWhites < byHighlights)
    }

    /** §3.1: 페이디드 매트 is defined by this — black stops being black. */
    @Test
    fun `a positive fade lifts black off the floor and leaves white alone`() {
        val black = luminanceOf(Ops.adjust(AdjustKind.Fade)(solid(Color.BLACK), 0.5f))
        val white = luminanceOf(Ops.adjust(AdjustKind.Fade)(solid(Color.WHITE), 0.5f))

        assertTrue("black should lift, got $black", black > 0.1f)
        assertEquals(1f, white, TOLERANCE)
    }

    @Test
    fun `a negative fade deepens black and still leaves white alone`() {
        val dark = luminanceOf(Ops.adjust(AdjustKind.Fade)(solid(Color.rgb(40, 40, 40)), -0.5f))
        val white = luminanceOf(Ops.adjust(AdjustKind.Fade)(solid(Color.WHITE), -0.5f))

        assertTrue("should darken, got $dark", dark < 40f / 255f)
        assertEquals(1f, white, TOLERANCE)
    }

    /**
     * The point of the s-curve over `Contrast`: it steepens the middle without moving the ends,
     * where a linear pivot clips both before it has bitten.
     */
    @Test
    fun `the s curve steepens the middle and leaves the ends where they were`() {
        val quarter = luminanceOf(Ops.adjust(AdjustKind.SCurve)(solid(Color.rgb(64, 64, 64)), 0.8f))
        val threeQuarter =
            luminanceOf(Ops.adjust(AdjustKind.SCurve)(solid(Color.rgb(191, 191, 191)), 0.8f))
        val black = luminanceOf(Ops.adjust(AdjustKind.SCurve)(solid(Color.BLACK), 0.8f))
        val white = luminanceOf(Ops.adjust(AdjustKind.SCurve)(solid(Color.WHITE), 0.8f))

        assertTrue("the low quarter should fall, got $quarter", quarter < 64f / 255f)
        assertTrue("the high quarter should rise, got $threeQuarter", threeQuarter > 191f / 255f)
        assertEquals(0f, black, TOLERANCE)
        assertEquals(1f, white, TOLERANCE)
    }

    /** Sharpen finds edges; clarity finds volume. A flat field has neither. */
    @Test
    fun `clarity leaves a flat field alone`() {
        val flat = solid(Color.rgb(120, 120, 120), size = 32)

        val output = Ops.adjust(AdjustKind.Clarity)(flat, 0.7f)

        assertEquals(flat.pixels(), output.pixels())
    }

    @Test
    fun `clarity changes a photograph that has structure`() {
        val input = fixture()

        val output = Ops.adjust(AdjustKind.Clarity)(input, 0.7f)

        assertTrue(input.pixels() != output.pixels())
    }

    // ---- fixtures --------------------------------------------------------

    private fun golden(name: String, kind: AdjustKind, value: Float) {
        GoldenAssert.assertMatchesGolden(name, Ops.adjust(kind)(fixture(), value))
    }

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    private fun solid(color: Int, size: Int = 8): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun Bitmap.pixels(): List<Int> =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }.toList()

    private fun luminanceOf(bitmap: Bitmap): Float {
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return luma(
            Color.red(pixel) / CHANNEL_MAX,
            Color.green(pixel) / CHANNEL_MAX,
            Color.blue(pixel) / CHANNEL_MAX,
        )
    }

    private companion object {
        const val TOLERANCE = 0.01f
        val TONE_KINDS = listOf(
            AdjustKind.Blacks,
            AdjustKind.Whites,
            AdjustKind.Fade,
            AdjustKind.SCurve,
            AdjustKind.Clarity,
        )
    }
}
