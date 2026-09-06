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
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.EditDocumentJson
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.imaging.model.Operation
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

/**
 * specs/outpaint.md §3, §4, §8. The one op that makes the canvas bigger, and the one thing that
 * makes it worth its guard: the interior is still the source's own pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutpaintTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- the document ----------------------------------------------------

    @Test
    fun `an outpaint is inserted first, whatever was already there`() {
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.4f)
            .withOutpaint(MARGINS, ImageRef("/outpaint_o.png"), id = "o")

        assertEquals("o", document.operations.first().id)
        assertEquals(2, document.operations.size)
        assertEquals(MARGINS, document.outpaint()!!.margins)
    }

    @Test
    fun `a second outpaint replaces the first and stays at index 0`() {
        val document = document()
            .withOutpaint(MARGINS, ImageRef("/outpaint_a.png"), id = "a")
            .withAdjust(AdjustKind.Exposure, 0.4f)
            .withOutpaint(Margins(left = 0.4f), ImageRef("/outpaint_b.png"), id = "b")

        val outpaint = document.outpaint()!!
        assertEquals(1, document.operations.filterIsInstance<Operation.Outpaint>().size)
        assertEquals(0, document.operations.indexOf(outpaint))
        assertEquals(ImageRef("/outpaint_b.png"), outpaint.resultRef)
        assertEquals(0.4f, outpaint.margins.left, 0f)
    }

    @Test
    fun `the margins are clamped by the model, not only by the tool`() {
        val document = document().withOutpaint(Margins(left = 0.9f), ImageRef("/o.png"))

        assertEquals(0.5f, document.outpaint()!!.margins.left, 0f)
    }

    /** §3: those ops carry pixels sized to the un-extended canvas. 확대 comes before 선택. */
    @Test
    fun `a document holding a mask refuses to outpaint at all`() {
        val masked = document().withMask(ImageRef("/mask_m.png"), id = "m")

        val after = masked.withOutpaint(MARGINS, ImageRef("/outpaint_o.png"))

        assertEquals(masked, after)
        assertEquals(false, masked.canOutpaint)
        assertEquals(true, document().canOutpaint)
    }

    @Test
    fun `a cut-out, an erase and a fill each refuse it too`() {
        val base = document().withMask(ImageRef("/mask_m.png"), id = "m")

        assertEquals(false, base.withCutOut("m").canOutpaint)
        assertEquals(false, base.withGenerativeErase("m", ImageRef("/e.png")).canOutpaint)
        assertEquals(false, base.withGenerativeFill("m", ImageRef("/f.png"), "우산").canOutpaint)
    }

    @Test
    fun `an existing crop is re-normalized into the expanded space`() {
        val cropped = document().withCrop(RECT, angleDeg = 3f)

        val expanded = cropped.withOutpaint(
            Margins(0.25f, 0.25f, 0.25f, 0.25f),
            ImageRef("/outpaint_o.png"),
        )

        val crop = expanded.crop()!!
        assertEquals(1f / 3f, crop.rect.left, TOLERANCE)
        assertEquals(2f / 3f, crop.rect.right, TOLERANCE)
        // The angle is unaffected: it is not measured against the canvas.
        assertEquals(3f, crop.angleDeg, 0f)
    }

    @Test
    fun `it round-trips through JSON, margins and all`() {
        val original = document().withOutpaint(MARGINS, ImageRef("/outpaint_o.png"), id = "o")

        val decoded = EditDocumentJson.decode(EditDocumentJson.encode(original))

        assertEquals(original, decoded)
        assertEquals(MARGINS, decoded.outpaint()!!.margins)
        assertTrue(EditDocumentJson.encode(original).contains("\"v\":1"))
    }

    @Test
    fun `an outpaint node missing a margin is dropped, not fatal`() {
        val text = """
            {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,
             "operations":[{"type":"outpaint","id":"o","resultRef":"/o.png",
             "left":0.1,"top":0.1,"right":0.1}]}
        """.trimIndent()

        assertEquals(emptyList<Operation>(), EditDocumentJson.decode(text).operations)
    }

    // ---- the render ------------------------------------------------------

    @Test
    fun `the canvas grows and the border comes from the stored result`() = runTest {
        val renderer = renderer()
        val plain = document()
        val source = bitmap(renderer.preview(plain, LONG_EDGE))

        val expanded = plain.withOutpaint(MARGINS, flatResult(source, MARGINS), id = "o")
        val output = bitmap(renderer.preview(expanded, LONG_EDGE))

        assertEquals(MARGINS.expandedWidth(source.width), output.width)
        assertEquals(MARGINS.expandedHeight(source.height), output.height)
        assertEquals(BORDER, output.getPixel(1, output.height / 2))
        GoldenAssert.assertMatchesGolden("outpaint_render", output)
    }

    /**
     * §3's whole argument: flattening would have capped the photograph at the model's output
     * resolution. Sampled well inside the ramp, the interior is still the source's own pixels.
     */
    @Test
    fun `the interior is the source's pixels, not the model's`() = runTest {
        val renderer = renderer()
        val plain = document()
        val source = bitmap(renderer.preview(plain, LONG_EDGE))

        val expanded = plain.withOutpaint(MARGINS, flatResult(source, MARGINS), id = "o")
        val output = bitmap(renderer.preview(expanded, LONG_EDGE))

        val left = MARGINS.padLeft(source.width)
        val top = MARGINS.padTop(source.height)
        for (y in RAMP until source.height - RAMP step STEP) {
            for (x in RAMP until source.width - RAMP step STEP) {
                assertEquals(
                    "interior pixel ($x, $y) is not the source's",
                    source.getPixel(x, y),
                    output.getPixel(left + x, top + y),
                )
            }
        }
    }

    /** §4: without the ramp the seam is four straight lines. */
    @Test
    fun `the seam is a ramp rather than a hard edge`() = runTest {
        val renderer = renderer()
        val plain = document()
        val source = bitmap(renderer.preview(plain, LONG_EDGE))

        val expanded = plain.withOutpaint(MARGINS, flatResult(source, MARGINS), id = "o")
        val output = bitmap(renderer.preview(expanded, LONG_EDGE))

        val left = MARGINS.padLeft(source.width)
        val y = MARGINS.padTop(source.height) + source.height / 2
        // The first interior column is the model's colour; a few pixels in it is not.
        assertEquals(BORDER, output.getPixel(left, y))
        assertNotEquals(BORDER, output.getPixel(left + RAMP, y))
    }

    @Test
    fun `an adjustment after an outpaint applies across the whole expanded canvas`() = runTest {
        val renderer = renderer()
        val plain = document()
        val source = bitmap(renderer.preview(plain, LONG_EDGE))

        val expanded = plain.withOutpaint(MARGINS, flatResult(source, MARGINS), id = "o")
        val before = bitmap(renderer.preview(expanded, LONG_EDGE))
        val after = bitmap(
            renderer.preview(expanded.withAdjust(AdjustKind.Exposure, 0.5f), LONG_EDGE),
        )

        assertEquals(before.width, after.width)
        assertNotEquals(
            "the border must be adjusted too",
            before.getPixel(1, before.height / 2),
            after.getPixel(1, after.height / 2),
        )
    }

    /** §4: export composes the stored PNG rather than dropping it, and progress still ends at 1. */
    @Test
    fun `a full render composes the border and reports progress that ends at one`() = runTest {
        val renderer = renderer()
        val plain = document()
        val source = bitmap(renderer.preview(plain, LONG_EDGE))
        val expanded = plain
            .withOutpaint(MARGINS, flatResult(source, MARGINS), id = "o")
            .withAdjust(AdjustKind.Contrast, 0.2f)

        val progress = mutableListOf<Float>()
        val output = bitmap(renderer.full(expanded) { progress += it })

        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)
        assertTrue("the canvas must have grown", output.width > source.width)
    }

    // ---- fixtures --------------------------------------------------------

    private fun document() = EditDocument(
        id = "doc",
        source = ImageRef(Fixtures.copyTo("photo_512.png", temp.newFolder()).absolutePath),
        createdAt = 0L,
        updatedAt = 0L,
    )

    /**
     * Stands in for the model's answer: the whole expanded canvas, flat, so "this pixel came
     * from the model" is a single colour comparison.
     */
    private fun flatResult(source: Bitmap, margins: Margins): ImageRef {
        val bitmap = Bitmap.createBitmap(
            margins.expandedWidth(source.width),
            margins.expandedHeight(source.height),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(BORDER)
        val file = File(temp.newFolder(), "outpaint_o.png")
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
        const val STEP = 13
        const val TOLERANCE = 1e-4f

        /** Wide enough that a sample inside it cannot be sitting in the seam's ramp. */
        const val RAMP = 16
        val MARGINS = Margins(left = 0.25f, top = 0.1f, right = 0.25f, bottom = 0.1f)
        val RECT = android.graphics.RectF(0.25f, 0.25f, 0.75f, 0.75f)
        val BORDER = Color.rgb(20, 120, 200)
    }
}
