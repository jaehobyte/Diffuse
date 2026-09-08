package com.diffuse.core.imaging.style

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** specs/style_match.md §9. */
@RunWith(AndroidJUnit4::class)
class StyleCatalogTest {

    private val presets = StyleCatalog.load(RuntimeEnvironment.getApplication().assets)

    @Test
    fun `every preset parses, and the catalog is the twelve styles decided on`() {
        assertEquals(EXPECTED_IDS, presets.map { it.id })
        assertTrue(presets.all { it.variants.isNotEmpty() })
    }

    @Test
    fun `a variant's id is derived from its style's, because styles json gives it none`() {
        val landscape = presets.first { it.id == "crisp-landscape" }
        assertEquals(
            listOf("crisp-landscape-1", "crisp-landscape-2", "crisp-landscape-3"),
            landscape.variants.map { it.id },
        )
    }

    @Test
    fun `every parameter maps to an AdjustKind or is on the recorded drop list`() {
        // §3.1's list is closed: anything else in styles.json must fail rather than vanish.
        assertEquals(
            setOf("grain", "color_grading", "dehaze", "bw_filter"),
            StyleParams.DROPPED,
        )
        assertTrue(presets.all { it.params.isNotEmpty() })
    }

    @Test
    fun `a style defined only by a dropped parameter keeps the rest of itself`() {
        // 클래식 모노's 레드 필터 드라마 carries bw_filter, which T71 did not add.
        val mono = presets.first { it.id == "bw-classic" }
        val redFilter = mono.variants.first { it.params.containsKey(AdjustKind.Saturation) }
        assertTrue(redFilter.params.isNotEmpty())
    }

    @Test
    fun `intensity scales linearly`() {
        val preset = presets.first { it.id == "film-warm" }
        val half = preset.params.atIntensity(HALF)
        preset.params.forEach { (kind, value) ->
            assertEquals(value / 2f, half.getValue(kind), TOLERANCE)
        }
    }

    @Test
    fun `intensity 0 is the identity`() {
        assertTrue(presets.all { it.params.atIntensity(0).isEmpty() })
    }

    @Test
    fun `intensity 100 changes nothing`() {
        val preset = presets.first { it.id == "vibrant-pop" }
        assertEquals(preset.params, preset.params.atIntensity(FULL))
    }

    private companion object {
        const val HALF = 50
        const val FULL = 100
        const val TOLERANCE = 1e-6f

        val EXPECTED_IDS = listOf(
            "clean-bright",
            "natural-enhance",
            "crisp-landscape",
            "film-warm",
            "faded-matte",
            "cinematic-teal",
            "moody-dark",
            "vibrant-pop",
            "pastel-soft",
            "golden-glow",
            "urban-hip",
            "bw-classic",
        )
    }
}
