package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.EditDocumentJson
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/generative_erase.md §6, §8. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerativeEraseTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the result replaces pixels inside the mask and nothing outside it`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val erased = plain
            .withMask(leftHalfMask(before.width, before.height), id = "m")
            .withGenerativeErase("m", solidResult(before.width, before.height), id = "e")
        val output = bitmap(renderer.preview(erased, LONG_EDGE))

        GoldenAssert.assertMatchesGolden("generative_erase_render", output)
        val half = before.width / 2
        for (y in 0 until before.height step STEP) {
            for (x in half until before.width step STEP) {
                assertEquals(
                    "pixel ($x, $y) outside the mask changed",
                    before.getPixel(x, y),
                    output.getPixel(x, y),
                )
            }
            for (x in 0 until half step STEP) {
                assertEquals("pixel ($x, $y) was not replaced", FILL, output.getPixel(x, y))
            }
        }
    }

    @Test
    fun `a result recorded at a smaller size is scaled up rather than regenerated`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, LONG_EDGE))

        val erased = plain
            .withMask(leftHalfMask(before.width, before.height), id = "m")
            .withGenerativeErase("m", solidResult(before.width / 4, before.height / 4), id = "e")
        val output = bitmap(renderer.preview(erased, LONG_EDGE))

        assertEquals(FILL, output.getPixel(2, 2))
        assertEquals(before.getPixel(before.width - 2, 2), output.getPixel(before.width - 2, 2))
    }

    @Test
    fun `it round-trips through JSON`() {
        val original = document()
            .withMask(ImageRef("/mask_m.png"), id = "m")
            .withGenerativeErase("m", ImageRef("/erase_e.png"), id = "e")

        val decoded = EditDocumentJson.decode(EditDocumentJson.encode(original))

        assertEquals(original, decoded)
        assertEquals("m", decoded.generativeErases().single().maskId)
        assertEquals(ImageRef("/erase_e.png"), decoded.generativeErases().single().resultRef)
    }

    @Test
    fun `an erase naming a missing mask refuses to load`() {
        val broken = document().withGenerativeErase("gone", ImageRef("/erase_e.png"), id = "e")

        assertFalse(broken.referencesResolve())
    }

    @Test
    fun `an erase node missing its resultRef is dropped, not fatal`() {
        val text = """
            {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,
             "operations":[{"type":"generativeErase","id":"e","maskId":"m"}]}
        """.trimIndent()

        assertEquals(emptyList<Any>(), EditDocumentJson.decode(text).operations)
    }

    // ---- fixtures --------------------------------------------------------

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

    /** Stands in for the model's output: a flat fill is unmistakable against the photo. */
    private fun solidResult(width: Int, height: Int): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(FILL)
        val file = File(temp.newFolder(), "erase_e.png")
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
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        const val STEP = 7
        val FILL = Color.rgb(20, 160, 90)
    }
}
