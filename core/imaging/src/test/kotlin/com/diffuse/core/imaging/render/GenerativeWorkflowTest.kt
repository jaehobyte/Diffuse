package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * specs/vibe_edit.md §9: a plan mixes a generative step with the manual tools' operations, and
 * the device report was that the combination silently dropped half of itself.
 *
 * The pure ordering rows — a masked adjust after an erase, an adjust before one, two erases, and
 * a cut-out before an adjust — are proven in [OperationOrderTest]. This file covers what a plan
 * actually produces on top of that: a *global* adjust after an erase, an erase the user then cuts
 * out, and the export-resolution path.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerativeWorkflowTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** "버스 지우고 사진 예쁘게 만들어줘": the adjustment is about the whole photo, hole included. */
    @Test
    fun `an erase then a global adjustment moves the filled area and the rest alike`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val document = plain
            .withMask(leftHalfMask(before.width, before.height), id = "m")
            .withGenerativeErase("m", solidResult(before.width, before.height, FILL), id = "e")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = null)
        val output = bitmap(renderer.preview(document, LONG_EDGE))

        val inside = output.getPixel(2, 2)
        val outside = output.getPixel(before.width - 2, 2)
        assertTrue(
            "the filled area should have been brightened with everything else",
            Color.green(inside) > Color.green(FILL),
        )
        assertTrue(
            "outside the erase should have been brightened too",
            Color.green(outside) > Color.green(before.getPixel(before.width - 2, 2)),
        )
    }

    @Test
    fun `an erase then a cut-out keeps the filled pixels and clears everything else`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val document = plain
            .withMask(leftHalfMask(before.width, before.height), id = "m")
            .withGenerativeErase("m", solidResult(before.width, before.height, FILL), id = "e")
            .withCutOut("m")
        val output = bitmap(renderer.preview(document, LONG_EDGE))

        assertEquals("the erased half survives the cut-out", FILL, opaque(output.getPixel(2, 2)))
        assertEquals(255, Color.alpha(output.getPixel(2, 2)))
        assertEquals(0, Color.alpha(output.getPixel(before.width - 2, 2)))
    }

    /** specs/generative_erase.md §11: export composites the pixels the user approved. */
    @Test
    fun `the full-resolution render composes a result recorded at preview size`() = runTest {
        val renderer = renderer()
        val plain = document()
        val preview = bitmap(renderer.preview(plain, SMALL_EDGE))

        val document = plain
            .withMask(leftHalfMask(preview.width, preview.height), id = "m")
            .withGenerativeErase("m", solidResult(preview.width, preview.height, FILL), id = "e")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = null)
        val full = bitmap(renderer.full(document))

        assertTrue("export should render larger than the preview", full.width > preview.width)
        assertTrue(
            "the stored result must still be there at full size",
            Color.green(full.getPixel(2, 2)) > Color.green(FILL),
        )
    }

    @Test
    fun `an erase whose mask went missing leaves the photo alone rather than failing`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))
        val mask = leftHalfMask(before.width, before.height)

        val document = plain
            .withMask(mask, id = "m")
            .withGenerativeErase("m", solidResult(before.width, before.height, FILL), id = "e")
        File(mask.path).delete()
        val output = bitmap(renderer.preview(document, LONG_EDGE))

        assertEquals(before.getPixel(2, 2), output.getPixel(2, 2))
    }

    // ---- fixtures --------------------------------------------------------

    private fun opaque(pixel: Int) = Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

    private fun document() = EditDocument(
        id = "doc",
        source = ImageRef(Fixtures.copyTo("photo_512.png", temp.newFolder()).absolutePath),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun leftHalfMask(width: Int, height: Int): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (x < width / 2) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        val file = File(temp.newFolder(), "mask_m.png")
        MaskIo.write(file, bitmap)
        return ImageRef(file.absolutePath)
    }

    private fun solidResult(width: Int, height: Int, color: Int): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(temp.newFolder(), "erase.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private fun bitmap(result: Result<Bitmap>): Bitmap = when (result) {
        is Result.Success -> result.value
        is Result.Failure -> throw AssertionError("render failed: ${result.error}")
    }

    private fun TestScope.renderer(): CpuRenderer {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val io = dispatcher
        }
        val loader = ImageLoader(
            RuntimeEnvironment.getApplication().contentResolver,
            dispatchers,
        ) { bytes, options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        return CpuRenderer(loader, dispatchers)
    }

    private companion object {
        const val LONG_EDGE = 512
        const val SMALL_EDGE = 128
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        const val EXPOSURE = 0.5f
        val FILL = Color.rgb(20, 160, 90)
    }
}
