package com.diffuse.core.imaging.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** specs/adjust_hsl.md §3: one conversion, shared by the renderer and the sheet's chips. */
@RunWith(AndroidJUnit4::class)
class HslColorTest {

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    @Test
    fun `rgb round trips through hsl within one step`() {
        val hsl = FloatArray(3)
        val samples = listOf(
            0xE60023, 0x1E7A46, 0x2E6BE6, 0xFFFFFF, 0x000000, 0x808080, 0xE0AC69, 0x00FFFF,
        )

        samples.forEach { rgb ->
            HslColor.fromRgb(
                channel(rgb, 16) / 255f,
                channel(rgb, 8) / 255f,
                channel(rgb, 0) / 255f,
                hsl,
            )
            val restored = HslColor.toRgb(hsl[0], hsl[1], hsl[2])

            intArrayOf(16, 8, 0).forEach { shift ->
                val delta = abs(channel(rgb, shift) - channel(restored, shift))
                assertTrue(
                    "channel at $shift drifted $delta for ${rgb.toString(16)}",
                    delta <= 1,
                )
            }
        }
    }

    @Test
    fun `toRgb leaves the alpha bits clear so a transform can keep the source alpha`() {
        val rgb = HslColor.toRgb(0f, 1f, 0.5f)

        assertEquals(0, rgb ushr 24)
    }

    @Test
    fun `a grey has no saturation and a band centre keeps its hue`() {
        val hsl = FloatArray(3)

        HslColor.fromRgb(0.5f, 0.5f, 0.5f, hsl)
        assertEquals(0f, hsl[1], 1e-4f)

        HslBand.entries.forEach { band ->
            val swatch = HslColor.toRgb(band.centerDeg, 0.8f, 0.5f)
            HslColor.fromRgb(
                channel(swatch, 16) / 255f,
                channel(swatch, 8) / 255f,
                channel(swatch, 0) / 255f,
                hsl,
            )
            assertEquals("${band.name} centre", band.centerDeg, hsl[0], 1f)
        }
    }
}
