package com.diffuse.core.imaging.style

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.render.LightOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** specs/style_match.md §9 — the conversion table, and only it, knows either scale. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StyleParamsTest {

    private val presets = StyleCatalog.load(RuntimeEnvironment.getApplication().assets)

    @Test
    fun `every converted value is inside its kind's range`() {
        // §9: a preset that clamps is a bug in the table, not in the preset — so nothing is
        // clamped on the way in, and this is what says the divisors are right.
        val all = presets.flatMap { preset ->
            preset.params.entries + preset.variants.flatMap { it.params.entries }
        }
        assertTrue(all.isNotEmpty())
        all.forEach { (kind, value) ->
            assertTrue("$kind = $value is outside ${kind.range}", value in kind.range)
        }
    }

    @Test
    fun `exposure is stops, and the divisor is the one LightOps uses`() {
        // 클린 브라이트 opens on +0.45 stops.
        val exposure = presets.first { it.id == "clean-bright" }.params
            .getValue(AdjustKind.Exposure)
        assertEquals(0.45f / STOPS, exposure, TOLERANCE)

        // The claim the divisor rests on: value 0.5 is one stop, so mid-grey doubles.
        val grey = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            .also { it.setPixel(0, 0, Color.rgb(GREY, GREY, GREY)) }
        val brighter = LightOps.exposure(grey, 0.5f)
        assertEquals(GREY * 2, Color.red(brighter.getPixel(0, 0)))
    }

    @Test
    fun `every other scalar is Lightroom's own full scale`() {
        // 클린 브라이트: shadows 35, whites 15, contrast 8, vibrance 18, temperature -4, clarity 6.
        val params = presets.first { it.id == "clean-bright" }.params
        assertEquals(0.35f, params.getValue(AdjustKind.Shadows), TOLERANCE)
        assertEquals(0.15f, params.getValue(AdjustKind.Whites), TOLERANCE)
        assertEquals(-0.04f, params.getValue(AdjustKind.Temperature), TOLERANCE)
    }

    @Test
    fun `vignette flips sign, because Lightroom darkens on a negative amount`() {
        // 무디 다크 asks for vignette -30; DetailOps darkens corners on a positive 0..1 value.
        val vignette = presets.first { it.id == "moody-dark" }.params
            .getValue(AdjustKind.Vignette)
        assertEquals(0.3f, vignette, TOLERANCE)
    }

    @Test
    fun `nested hsl becomes one AdjustKind per band and channel`() {
        // 청량 크리스프: blue {sat 35, lum -10}, green {sat 20}.
        val params = presets.first { it.id == "crisp-landscape" }.params
        assertEquals(0.35f, params.getValue(AdjustKind.HslBlueSaturation), TOLERANCE)
        assertEquals(-0.1f, params.getValue(AdjustKind.HslBlueLuminance), TOLERANCE)
        assertEquals(0.2f, params.getValue(AdjustKind.HslGreenSaturation), TOLERANCE)
    }

    private companion object {
        const val STOPS = 2f
        const val GREY = 60
        const val TOLERANCE = 1e-6f
    }
}
