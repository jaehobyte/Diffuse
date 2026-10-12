package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.model.RetouchKind
import com.diffuse.core.imaging.model.SkinRetouchSettings
import com.diffuse.core.imaging.model.skinRetouchBase
import com.diffuse.core.imaging.model.skinRetouchInsertIndex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** specs/skin_retouch_pipeline.md §4 and §6: the stored result, replayed in list order. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinRetouchRenderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `inside the support only RGB is replaced and the input alpha is kept`() = runTest {
        val renderer = renderer()
        val plain = document(translucentTop = true)
        val before = bitmap(renderer.preview(plain, WIDTH))

        val retouched = plain.retouched(result(WIDTH, HEIGHT), leftHalfMask(WIDTH, HEIGHT))
        val output = bitmap(renderer.preview(retouched, WIDTH))

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val input = before.getPixel(x, y)
                val pixel = output.getPixel(x, y)
                if (x >= WIDTH / 2) {
                    assertEquals("($x, $y) outside the support changed", input, pixel)
                    continue
                }
                assertEquals("($x, $y) alpha", Color.alpha(input), Color.alpha(pixel))
                // A translucent pixel goes through premultiplied storage, so allow its rounding.
                val tolerance = if (Color.alpha(input) == OPAQUE) 0 else 2
                assertClose("($x, $y) red", Color.red(FILL), Color.red(pixel), tolerance)
                assertClose("($x, $y) green", Color.green(FILL), Color.green(pixel), tolerance)
                assertClose("($x, $y) blue", Color.blue(FILL), Color.blue(pixel), tolerance)
            }
        }
        assertEquals(TRANSLUCENT, Color.alpha(output.getPixel(2, 2)))
    }

    @Test
    fun `a missing result or support leaves the input unchanged`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, WIDTH))

        val noResult = plain.retouched(ImageRef(File(temp.root, "gone.png").path), leftHalfMask(WIDTH, HEIGHT))
        val noSupport = plain.retouched(result(WIDTH, HEIGHT), ImageRef(File(temp.root, "gone_mask.png").path))

        assertTrue(bitmap(renderer.preview(noResult, WIDTH)).sameAs(before))
        assertTrue(bitmap(renderer.preview(noSupport, WIDTH)).sameAs(before))
    }

    @Test
    fun `an Adjust after the retouch applies once, on top of it`() = runTest {
        val renderer = renderer()
        val adjusted = document().withAdjust(AdjustKind.Exposure, EXPOSURE)
        val base = bitmap(renderer.preview(adjusted.skinRetouchBase(adjusted.skinRetouchInsertIndex()), WIDTH))
        val fill = solid(WIDTH, HEIGHT)
        val support = halfMaskBitmap(WIDTH, HEIGHT)

        val retouched = adjusted.retouched(result(WIDTH, HEIGHT), leftHalfMask(WIDTH, HEIGHT))
        val output = bitmap(renderer.preview(retouched, WIDTH))
        val manual = LightOps.exposure(SkinRetouchOp.apply(base, fill, support), EXPOSURE)

        assertTrue(retouched.operations.last() is Operation.Adjust)
        assertTrue("render differs from retouch-then-exposure", output.sameAs(manual))
        assertNotEquals(FILL, output.getPixel(2, 2))
    }

    @Test
    fun `the crop still runs last`() = runTest {
        val renderer = renderer()
        val cropped = document().withCrop(RectF(0f, 0f, 0.5f, 1f), 0f)

        val retouched = cropped.retouched(result(WIDTH, HEIGHT), leftHalfMask(WIDTH, HEIGHT))
        val output = bitmap(renderer.preview(retouched, WIDTH))

        assertEquals(WIDTH / 2, output.width)
        assertEquals(FILL, output.getPixel(1, 1))
        assertEquals(FILL, output.getPixel(output.width - 1, output.height - 1))
    }

    @Test
    fun `a working-size result renders the same at preview and at full size`() = runTest {
        val renderer = renderer()
        val plain = document()
        val retouched = plain.retouched(result(WIDTH, HEIGHT), leftHalfMask(WIDTH, HEIGHT))

        val full = bitmap(renderer.full(retouched))
        val small = bitmap(renderer.preview(retouched, WIDTH / 2))
        val smallBefore = bitmap(renderer.preview(plain, WIDTH / 2))

        assertEquals(WIDTH, full.width)
        assertEquals(FILL, full.getPixel(WIDTH / 2 - 1, HEIGHT - 1))
        assertEquals(WIDTH / 2, small.width)
        assertEquals(FILL, small.getPixel(1, 1))
        assertEquals(smallBefore.getPixel(small.width - 2, 1), small.getPixel(small.width - 2, 1))
    }

    @Test
    fun `a smaller stored result and support are scaled to the render size`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, WIDTH))

        val retouched = plain.retouched(result(WIDTH / 2, HEIGHT / 2), leftHalfMask(WIDTH / 2, HEIGHT / 2))
        val output = bitmap(renderer.preview(retouched, WIDTH))

        assertEquals(FILL, output.getPixel(2, 2))
        assertEquals(before.getPixel(WIDTH - 2, 2), output.getPixel(WIDTH - 2, 2))
    }

    @Test
    fun `a draft drawn from transient bitmaps needs no files and falls back once removed`() = runTest {
        val renderer = renderer()
        val plain = document()
        val before = bitmap(renderer.preview(plain, WIDTH))
        val resultRef = ImageRef(File(temp.root, "retouch_draft.png").path)
        val maskRef = ImageRef(File(temp.root, "mask_draft.png").path)
        val draft = plain.retouched(resultRef, maskRef)

        // Half the canvas scale, as a draft result may be.
        renderer.putTransient(resultRef, solid(WIDTH / 2, HEIGHT / 2))
        renderer.putTransient(maskRef, halfMaskBitmap(WIDTH / 2, HEIGHT / 2))
        val drawn = bitmap(renderer.preview(draft, WIDTH))
        renderer.putTransient(resultRef, solid(WIDTH / 2, HEIGHT / 2).apply { eraseColor(SECOND_FILL) })
        val moved = bitmap(renderer.preview(draft, WIDTH))
        renderer.removeTransient(resultRef)
        renderer.removeTransient(maskRef)
        val removed = bitmap(renderer.preview(draft, WIDTH))

        assertEquals(FILL, drawn.getPixel(2, 2))
        assertEquals(before.getPixel(WIDTH - 2, 2), drawn.getPixel(WIDTH - 2, 2))
        assertEquals("a changed transient behind the same ref must redraw", SECOND_FILL, moved.getPixel(2, 2))
        assertTrue("the missing files must leave the input unchanged", removed.sameAs(before))
    }

    // ---- fixtures --------------------------------------------------------

    private fun EditDocument.retouched(resultRef: ImageRef, maskRef: ImageRef) = withSkinRetouch(
        maskRef = maskRef,
        resultRef = resultRef,
        settings = SkinRetouchSettings(
            strengths = RetouchKind.entries.associateWith { 0f } + (RetouchKind.Blemish to 1f),
            engines = mapOf(RetouchKind.Blemish to "engine@1"),
        ),
        maskId = "s",
        id = "r",
        insertIndex = skinRetouchInsertIndex(),
    )

    /** A generated PNG, so its alpha is known exactly; the top half is translucent on request. */
    private fun document(translucentTop: Boolean = false): EditDocument {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val alpha = if (translucentTop && y < HEIGHT / 2) TRANSLUCENT else OPAQUE
                bitmap.setPixel(x, y, Color.argb(alpha, x * 3, y * 5, 120))
            }
        }
        val file = File(temp.newFolder(), "source.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return EditDocument(id = "doc", source = ImageRef(file.absolutePath), createdAt = 0L, updatedAt = 0L)
    }

    private fun halfMaskBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (x < width / 2) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        return bitmap
    }

    private fun leftHalfMask(width: Int, height: Int): ImageRef {
        val file = File(temp.newFolder(), "mask_s.png")
        MaskIo.write(file, halfMaskBitmap(width, height))
        return ImageRef(file.absolutePath)
    }

    private fun solid(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(FILL) }

    private fun result(width: Int, height: Int): ImageRef {
        val file = File(temp.newFolder(), "retouch_r.png")
        file.outputStream().use { solid(width, height).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private fun assertClose(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= tolerance)
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
        const val WIDTH = 64
        const val HEIGHT = 48
        const val OPAQUE = 255
        const val TRANSLUCENT = 128
        const val ALPHA_SHIFT = 24
        const val EXPOSURE = 0.5f
        val FILL = Color.rgb(20, 160, 90)
        val SECOND_FILL = Color.rgb(200, 40, 40)
    }
}
