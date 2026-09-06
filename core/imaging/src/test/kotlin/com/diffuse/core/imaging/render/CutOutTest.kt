package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.min

/** specs/selection_tool.md §8.2. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CutOutTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `alpha outside the mask is cleared and inside is kept`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val output = bitmap(renderer.preview(cutOutDocument(before), LONG_EDGE))

        GoldenAssert.assertMatchesGolden("cutout_render", output)
        assertEquals(0, output.getPixel(output.width - 2, 2) ushr ALPHA_SHIFT)
        assertEquals(OPAQUE, output.getPixel(2, 2) ushr ALPHA_SHIFT)
    }

    @Test
    fun `the colour channels are untouched where the mask keeps the pixel`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val output = bitmap(renderer.preview(cutOutDocument(before), LONG_EDGE))

        val half = before.width / 2
        for (y in 0 until before.height step STEP) {
            for (x in 0 until half step STEP) {
                assertEquals(
                    "pixel ($x, $y) inside the mask changed",
                    before.getPixel(x, y) and RGB_MASK,
                    output.getPixel(x, y) and RGB_MASK,
                )
            }
        }
    }

    @Test
    fun `two cut-outs each restrict the alpha further`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))
        val left = leftHalfMask(before.width, before.height)
        val top = topHalfMask(before.width, before.height)

        val both = plain
            .withMask(left, id = "a")
            .withMask(top, id = "b")
            .withCutOut("a", id = "c1")
            .withCutOut("b", id = "c2")
        val output = bitmap(renderer.preview(both, LONG_EDGE))

        // Only the top-left quadrant survives both.
        assertEquals(OPAQUE, output.getPixel(2, 2) ushr ALPHA_SHIFT)
        assertEquals(0, output.getPixel(2, before.height - 2) ushr ALPHA_SHIFT)
        assertEquals(0, output.getPixel(before.width - 2, 2) ushr ALPHA_SHIFT)
    }

    @Test
    fun `hasAlpha reflects a cut-out, and a jpeg source without one does not`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        assertFalse(plain.hasAlpha)
        assertTrue(cutOutDocument(before).hasAlpha)
    }

    @Test
    fun `a cut-out naming a missing mask refuses to load`() {
        val plain = document()

        assertFalse(plain.withCutOut("gone").referencesResolve())
    }

    // ---- fixtures --------------------------------------------------------

    private fun cutOutDocument(preview: Bitmap): EditDocument = document()
        .withMask(leftHalfMask(preview.width, preview.height), id = "m")
        .withCutOut("m", id = "c")

    /** photo_512.png is opaque, so its `.png` extension would claim alpha; copy it as jpg. */
    private fun document(): EditDocument {
        val source = Fixtures.copyTo("photo_512.png", temp.newFolder())
        val jpg = File(source.parentFile, "source.jpg")
        source.copyTo(jpg, overwrite = true)
        return EditDocument("doc", ImageRef(jpg.absolutePath), createdAt = 0L, updatedAt = 0L)
    }

    private fun leftHalfMask(width: Int, height: Int) =
        mask(width, height) { x, _ -> x < width / 2 }

    private fun topHalfMask(width: Int, height: Int) =
        mask(width, height) { _, y -> y < height / 2 }

    private fun mask(width: Int, height: Int, set: (Int, Int) -> Boolean): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (set(x, y)) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        val file = File(temp.newFolder(), "mask.png")
        MaskIo.write(file, bitmap)
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
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        const val RGB_MASK = 0x00FFFFFF
        const val STEP = 7
    }
}
