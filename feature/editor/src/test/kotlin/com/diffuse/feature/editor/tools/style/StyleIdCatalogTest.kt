package com.diffuse.feature.editor.tools.style

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.StyleId
import com.diffuse.core.imaging.style.StyleCatalog
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

    @Test
    fun `every planner id has a Korean name to render in the step list`() {
        StyleId.entries.forEach { id ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertTrue(id.id, context.getString(styleNameRes(id.id)).isNotBlank())
        }
    }
}
