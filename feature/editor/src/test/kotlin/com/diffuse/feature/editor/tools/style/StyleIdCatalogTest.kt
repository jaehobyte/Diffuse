package com.diffuse.feature.editor.tools.style

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.StyleId
import com.diffuse.core.imaging.style.StyleCatalog
import com.diffuse.core.imaging.style.atIntensity
import com.diffuse.feature.editor.tools.direct.styleEffectsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * specs/style_match.md §6. `apply_style`'s enum lives in `core:ai` and the catalog in
 * `core:imaging`, because `core:ai` cannot read an asset. Nothing but this test — in the module
 * that can see both — says the two still agree, and a plan naming a style the catalog dropped
 * would otherwise fail silently at run time.
 */
@RunWith(AndroidJUnit4::class)
class StyleIdCatalogTest {

    private val presets = StyleCatalog.load(
        ApplicationProvider.getApplicationContext<Context>().assets,
    )

    @Test
    fun `the planner's enum is the catalog, in catalog order`() {
        assertEquals(presets.map { it.id }, StyleId.entries.map { it.id })
    }

    /**
     * specs/vibe_edit.md §15: what the planner is told a style writes is exactly what `PlanRunner`
     * and the 스타일 sheet commit — the catalog's converted values, scaled by `atIntensity`, with
     * the engine's dropped keys (grain, colour grading …) nowhere.
     */
    @Test
    fun `the plan context's style effects are the catalog's own values`() {
        val effects = styleEffectsOf(presets)

        assertEquals(StyleId.entries, effects.map { it.style })
        effects.zip(presets).forEach { (effect, preset) ->
            assertEquals(preset.id, preset.params.atIntensity(FULL), effect.values)
            assertEquals(preset.params.atIntensity(HALF), effect.values.atIntensity(HALF))
            assertTrue(effect.values.values.none { it == 0f })
        }
    }

    @Test
    fun `every planner id has a Korean name to render in the step list`() {
        StyleId.entries.forEach { id ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertTrue(id.id, context.getString(styleNameRes(id.id)).isNotBlank())
        }
    }

    private companion object {
        const val FULL = 100
        const val HALF = 50
    }
}
