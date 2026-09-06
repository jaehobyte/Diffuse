package com.diffuse.feature.editor.tools.style

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.style.StyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * specs/style_match.md §9: an `id` with no `style_name_<id>` fails a test rather than rendering a
 * raw id. A missing entry already fails to compile; this is what catches a *new* preset arriving
 * in `styles.json` with no Korean beside it.
 */
@RunWith(AndroidJUnit4::class)
class StyleLabelsTest {

    @Test
    fun `every preset in the catalog has a name`() {
        val context = RuntimeEnvironment.getApplication()
        val presets = StyleCatalog.load(context.assets)
        assertTrue(presets.isNotEmpty())
        presets.forEach { preset ->
            val name = context.getString(styleNameRes(preset.id))
            assertTrue("${preset.id} has an empty name", name.isNotBlank())
        }
    }

    @Test
    fun `the id joins the two, dashes and all`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("웜 필름", context.getString(styleNameRes("film-warm")))
    }
}
