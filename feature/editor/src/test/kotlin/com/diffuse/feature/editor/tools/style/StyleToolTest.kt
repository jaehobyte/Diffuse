package com.diffuse.feature.editor.tools.style

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.core.imaging.style.StyleCatalog
import com.diffuse.core.imaging.style.StylePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/style_match.md §4, §7, §9. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StyleToolTest {

    /** Bare on purpose: what 적용 adds is then exactly what the preset carries. */
    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private lateinit var presets: List<StylePreset>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        presets = StyleCatalog.load(
            ApplicationProvider.getApplicationContext<android.content.Context>().assets,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * `runTest`'s own scope is a `StandardTestDispatcher`, which queues `open`'s launch instead of
     * running it. The controller's real scope is `viewModelScope` on Main, so an unconfined one is
     * the faithful stand-in.
     */
    private fun controller(renderer: Renderer = FakeRenderer()) = StyleController(
        catalog = { presets },
        renderer = renderer,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    /** §4: 원본 is first and always present, so nothing is selected when the sheet opens. */
    @Test
    fun `the sheet opens on 원본 and has nothing to apply`() = runTest {
        val style = controller()
        style.open(document)

        assertNull(style.state.value.selected)
        assertFalse(style.state.value.canApply)
        assertEquals(presets, style.state.value.presets)
    }

    /** §7: one tile per preset plus 원본, in catalog order, appearing as they finish. */
    @Test
    fun `every preset gets a tile, and 원본 gets one too`() = runTest {
        val style = controller()
        style.open(document)

        val tiles = style.state.value.tiles
        assertTrue(tiles.containsKey(STYLE_NONE_ID))
        presets.forEach { assertTrue("${it.id} has no tile", tiles.containsKey(it.id)) }
        assertEquals(presets.size + 1, tiles.size)
    }

    /** §7: a tile that has not rendered yet is simply absent — the sheet draws it flat. */
    @Test
    fun `a tile that fails to render is absent rather than blank`() = runTest {
        val style = controller(FailingRenderer())
        style.open(document)

        assertTrue(style.state.value.tiles.isEmpty())
        assertEquals(presets, style.state.value.presets)
    }

    /** §4: selecting a tile applies live — and commits nothing. */
    @Test
    fun `selecting a tile changes what the canvas shows and commits nothing`() = runTest {
        val style = controller()
        style.open(document)

        style.select("film-warm")

        val shown = style.state.value.appliedTo(document)
        assertTrue(shown.operations.isNotEmpty())
        // The document itself is untouched: nothing is pushed until 적용.
        assertTrue(document.operations.isEmpty())
    }

    /** §4: 적용 commits every `Adjust` the preset carries, as one entry. */
    @Test
    fun `적용 commits every adjustment the preset carries`() = runTest {
        val style = controller()
        style.open(document)
        style.select("film-warm")

        val applied = requireNotNull(style.apply(document))

        val preset = presets.first { it.id == "film-warm" }
        val added = applied.operations.filterIsInstance<Operation.Adjust>()
        assertEquals(preset.params.size, added.size)
        preset.params.forEach { (kind, value) ->
            assertEquals(value, added.first { it.kind == kind }.value, TOLERANCE)
        }
    }

    /** §4: the 강도 slider scales **what is committed**, not only what is shown. */
    @Test
    fun `강도 scales what is committed`() = runTest {
        val style = controller()
        style.open(document)
        style.select("film-warm")
        style.setIntensity(HALF)

        val applied = requireNotNull(style.apply(document))
        val preset = presets.first { it.id == "film-warm" }
        preset.params.forEach { (kind, value) ->
            val committed = applied.operations.filterIsInstance<Operation.Adjust>()
                .last { it.kind == kind }
            assertEquals(value / 2f, committed.value, TOLERANCE)
        }
    }

    /** §4: 강도 0 is 원본 again — there is nothing left to commit. */
    @Test
    fun `강도 0 commits nothing`() = runTest {
        val style = controller()
        style.open(document)
        style.select("film-warm")
        style.setIntensity(0)

        assertEquals(document, style.apply(document))
    }

    /** §3: a variant is a second decision, and picking a different style drops it. */
    @Test
    fun `a variant replaces the style's own parameters, and a new style drops it`() = runTest {
        val style = controller()
        style.open(document)
        style.select("film-warm")
        val variant = presets.first { it.id == "film-warm" }.variants[1]
        style.selectVariant(variant.id)

        val scaled = style.state.value.scaled()
        assertEquals(variant.params.keys, scaled.keys)
        variant.params.forEach { (kind, value) ->
            assertEquals(value, scaled.getValue(kind), TOLERANCE)
        }

        style.select("moody-dark")
        assertNull(style.state.value.variant)
    }

    /** DESIGN.md §7: 취소 leaves the document byte-for-byte, and the tiles survive for next time. */
    @Test
    fun `취소 restores byte-for-byte and keeps the tiles`() = runTest {
        val style = controller()
        style.open(document)
        style.select("film-warm")
        style.setIntensity(HALF)

        style.close()

        assertNull(style.state.value.selected)
        assertEquals(STYLE_INTENSITY_MAX, style.state.value.intensity)
        assertEquals(document, style.state.value.appliedTo(document))
        assertEquals(presets.size + 1, style.state.value.tiles.size)
    }

    private open class FakeRenderer : Renderer {
        override suspend fun preview(
            document: EditDocument,
            targetLongEdgePx: Int,
        ): Result<Bitmap> =
            Result.Success(Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888))

        override suspend fun full(
            document: EditDocument,
            onProgress: (Float) -> Unit,
        ): Result<Bitmap> =
            Result.Success(Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class FailingRenderer : FakeRenderer() {
        override suspend fun preview(
            document: EditDocument,
            targetLongEdgePx: Int,
        ): Result<Bitmap> = Result.Failure(AppError.Io(java.io.IOException("no tile")))
    }

    private companion object {
        const val HALF = 50
        const val TILE_PX = 8
        const val TOLERANCE = 1e-6f
    }
}
