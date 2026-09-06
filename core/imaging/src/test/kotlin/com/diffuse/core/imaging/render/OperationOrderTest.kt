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
 * specs/generative_erase.md §10: "Ops added after the erase apply on top of it, in list order."
 *
 * Until T49 the renderer grouped by type — every adjust, then every erase — so a masked
 * adjustment made *after* an erase was computed and then overwritten by the erase result. On a
 * device that read as "지우기 되면 그 이후에 채도가 적용이 안 되네".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperationOrderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an adjustment after an erase is visible inside the erased region`() = runTest {
        val renderer = renderer()
        val plain = document()
        val size = bitmap(renderer.preview(plain, LONG_EDGE))

        val erasedThenAdjusted = plain
            .withMask(leftHalfMask(size.width, size.height), id = "m")
            .withGenerativeErase("m", solidResult(size.width, size.height, FILL), id = "e")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = "m")
        val output = bitmap(renderer.preview(erasedThenAdjusted, LONG_EDGE))

        val inside = output.getPixel(2, 2)
        assertTrue(
            "the erase result should have been brightened, was ${hex(inside)}",
            Color.green(inside) > Color.green(FILL),
        )
        assertEquals(
            "outside the mask nothing should have moved",
            size.getPixel(size.width - 2, 2),
            output.getPixel(size.width - 2, 2),
        )
    }

    @Test
    fun `an adjustment before an erase is overwritten by it, which is also list order`() = runTest {
        val renderer = renderer()
        val plain = document()
        val size = bitmap(renderer.preview(plain, LONG_EDGE))

        val adjustedThenErased = plain
            .withMask(leftHalfMask(size.width, size.height), id = "m")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = "m")
            .withGenerativeErase("m", solidResult(size.width, size.height, FILL), id = "e")
        val output = bitmap(renderer.preview(adjustedThenErased, LONG_EDGE))

        assertEquals(FILL, output.getPixel(2, 2))
    }

    @Test
    fun `two erases stack in list order`() = runTest {
        val renderer = renderer()
        val plain = document()
        val size = bitmap(renderer.preview(plain, LONG_EDGE))

        val twice = plain
            .withMask(leftHalfMask(size.width, size.height), id = "m")
            .withGenerativeErase("m", solidResult(size.width, size.height, FILL), id = "e1")
            .withGenerativeErase("m", solidResult(size.width, size.height, SECOND_FILL), id = "e2")
        val output = bitmap(renderer.preview(twice, LONG_EDGE))

        assertEquals(SECOND_FILL, output.getPixel(2, 2))
    }

    @Test
    fun `a global adjustment after a cut-out keeps the alpha the cut-out took`() = runTest {
        val renderer = renderer()
        val plain = document()
        val size = bitmap(renderer.preview(plain, LONG_EDGE))

        val cutThenAdjusted = plain
            .withMask(leftHalfMask(size.width, size.height), id = "m")
            .withCutOut("m")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = null)
        val output = bitmap(renderer.preview(cutThenAdjusted, LONG_EDGE))

        assertEquals("outside the cut-out must stay transparent", 0, Color.alpha(output.getPixel(size.width - 2, 2)))
        assertEquals("inside the cut-out must stay opaque", 255, Color.alpha(output.getPixel(2, 2)))
    }

    @Test
    fun `progress still ends at one`() = runTest {
        val renderer = renderer()
        val plain = document()
        val size = bitmap(renderer.preview(plain, LONG_EDGE))
        val document = plain
            .withMask(leftHalfMask(size.width, size.height), id = "m")
            .withGenerativeErase("m", solidResult(size.width, size.height, FILL), id = "e")
            .withAdjust(AdjustKind.Exposure, EXPOSURE, maskId = "m")

        val reported = mutableListOf<Float>()
        renderer.full(document) { reported += it }

        assertEquals(1f, reported.last(), 0f)
        assertEquals(reported, reported.sorted())
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

    private fun solidResult(width: Int, height: Int, color: Int): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(temp.newFolder(), "erase.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private fun hex(color: Int) = "#%08X".format(color)

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
        const val EXPOSURE = 0.5f
        val FILL = Color.rgb(20, 160, 90)
        val SECOND_FILL = Color.rgb(200, 40, 40)
    }
}
