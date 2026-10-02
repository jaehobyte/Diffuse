package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HeroMask
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.Timeline
import com.diffuse.core.imaging.model.TimelineLayout
import com.diffuse.core.imaging.model.withMultiShot
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

/**
 * specs/multishot.md §4, §5, §9: source-over with the destination's alpha, one multiplication of
 * soft alpha by opacity, the shared transform, clipping, ordering, and the ops around it — through
 * the production `CpuRenderer` and real PNG files.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiShotRenderTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- compositing -------------------------------------------------------------------------

    @Test
    fun `a translucent subject over an opaque background is source-over`() = runTest {
        val document = composite(opaqueSource(BLUE), shot(solid(RED), opacity = HALF))

        val pixel = render(document).getPixel(CENTRE, CENTRE)

        // out = F·a + B·(1 − a), a = 128/255.
        assertClose("red", 128, Color.red(pixel))
        assertClose("blue", 127, Color.blue(pixel))
        assertEquals(OPAQUE, Color.alpha(pixel))
    }

    @Test
    fun `over a translucent background the destination alpha is composited too`() = runTest {
        val document = composite(translucentSource(GREEN, alpha = 128), shot(solid(RED), opacity = HALF))

        val pixel = render(document).getPixel(CENTRE, CENTRE)

        // aOut = aS + aD(1 − aS) = 0.502 + 0.502 × 0.498 → 192; colours are un-premultiplied.
        assertClose("alpha", 192, Color.alpha(pixel))
        assertClose("red", 170, Color.red(pixel), tolerance = 3)
        assertClose("green", 85, Color.green(pixel), tolerance = 3)
    }

    @Test
    fun `a soft subject alpha is multiplied by opacity once`() = runTest {
        val soft = solid(WHITE, alpha = 100)
        val full = render(composite(translucentSource(BLACK, alpha = 0), shot(soft, opacity = 1f)))
        val half = render(composite(translucentSource(BLACK, alpha = 0), shot(soft, opacity = HALF)))

        assertClose("opacity 1 keeps the subject's alpha", 100, Color.alpha(full.getPixel(CENTRE, CENTRE)))
        assertClose("opacity 0.5 halves it once", 50, Color.alpha(half.getPixel(CENTRE, CENTRE)))
        // Premultiplied storage and filtering must not darken or lighten a soft edge.
        assertClose("no halo", 255, Color.red(half.getPixel(CENTRE, CENTRE)), tolerance = 4)
    }

    @Test
    fun `opacity zero changes nothing`() = runTest {
        val source = opaqueSource(BLUE)
        val bare = render(EditDocument("d", source, createdAt = 0L, updatedAt = 0L))

        val composited = render(composite(source, shot(solid(RED), opacity = 0f)))

        assertTrue(composited.sameAs(bare))
    }

    @Test
    fun `a transparent source keeps its alpha outside the subject`() = runTest {
        val subject = transparent().apply { square(this, 0, 0, SIZE / 4, RED) }
        val output = render(composite(translucentSource(GREEN, alpha = 60), shot(subject, opacity = 1f)))

        assertEquals(60, Color.alpha(output.getPixel(SIZE - 2, SIZE - 2)))
        assertEquals(OPAQUE, Color.alpha(output.getPixel(2, 2)))
    }

    @Test
    fun `shots are drawn in list order, so swapping them swaps what is on top`() = runTest {
        val red = shot(solid(RED), opacity = 1f, id = "a")
        val green = shot(solid(GREEN), opacity = 1f, id = "b")
        val source = opaqueSource(BLUE)

        val greenOnTop = render(composite(source, red, green)).getPixel(CENTRE, CENTRE)
        val redOnTop = render(composite(source, green, red)).getPixel(CENTRE, CENTRE)

        assertEquals(GREEN, greenOnTop)
        assertEquals(RED, redOnTop)
    }

    // ---- geometry ----------------------------------------------------------------------------

    @Test
    fun `a same-size photo lands where it was taken`() = runTest {
        val subject = transparent().apply { square(this, 6, 20, 4, RED) }

        val output = render(composite(opaqueSource(BLUE), shot(subject, opacity = 1f)))

        assertEquals(RED, output.getPixel(7, 21))
        assertEquals(BLUE, output.getPixel(12, 21))
    }

    @Test
    fun `a thin opaque line survives the composite exactly`() = runTest {
        // The golf club: one pixel wide, and it must neither blur away nor spread.
        val subject = transparent().apply { for (y in 0 until SIZE) setPixel(20, y, WHITE) }

        val output = render(composite(opaqueSource(BLUE), shot(subject, opacity = 1f)))

        for (y in 0 until SIZE) {
            assertEquals("line at y=$y", WHITE, output.getPixel(20, y))
            assertEquals("left of line at y=$y", BLUE, output.getPixel(19, y))
            assertEquals("right of line at y=$y", BLUE, output.getPixel(21, y))
        }
    }

    @Test
    fun `a different aspect is contained and centred`() = runTest {
        // 20×10 into 40×40: scale 2, so it covers the band y 10..30.
        val wide = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(RED) }

        val output = render(composite(opaqueSource(BLUE), shot(wide, opacity = 1f)))

        assertEquals(BLUE, output.getPixel(CENTRE, 5))
        assertEquals(RED, output.getPixel(CENTRE, CENTRE))
        assertEquals(BLUE, output.getPixel(CENTRE, 35))
        assertEquals(RED, output.getPixel(1, CENTRE))
    }

    @Test
    fun `rotation turns the photo about its centre`() = runTest {
        val wide = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(RED) }

        val output = render(composite(opaqueSource(BLUE), shot(wide, opacity = 1f, rotationDeg = 90f)))

        assertEquals(BLUE, output.getPixel(5, CENTRE))
        assertEquals(RED, output.getPixel(CENTRE, 35))
    }

    @Test
    fun `a shot moved off the canvas is clipped and the canvas keeps its size`() = runTest {
        val output = render(composite(opaqueSource(BLUE), shot(solid(RED), opacity = 1f, offsetX = 0.9f)))

        assertEquals(SIZE, output.width)
        assertEquals(SIZE, output.height)
        assertEquals(BLUE, output.getPixel(30, CENTRE))
        assertEquals(RED, output.getPixel(38, CENTRE))
    }

    @Test
    fun `preview and full put the subject at the same normalised place`() = runTest {
        val subject = transparent().apply { square(this, 8, 24, 6, RED) }
        val document = composite(
            opaqueSource(BLUE),
            shot(subject, opacity = 1f, offsetX = 0.1f, offsetY = -0.2f, scale = 1.5f, rotationDeg = 30f),
        )
        val renderer = renderer()

        val full = bitmap(renderer.full(document))
        val preview = bitmap(renderer.preview(document, SIZE / 2))

        val (fullX, fullY) = redCentroid(full)
        val (previewX, previewY) = redCentroid(preview)
        assertEquals(fullX, previewX, 1f / (SIZE / 2))
        assertEquals(fullY, previewY, 1f / (SIZE / 2))
    }

    // ---- the ops around it -------------------------------------------------------------------

    @Test
    fun `a crop and straighten run after the composite, on it`() = runTest {
        val subject = transparent().apply {
            for (y in 0 until SIZE) for (x in SIZE / 2 until SIZE) setPixel(x, y, RED)
        }
        val composited = composite(opaqueSource(BLUE), shot(subject, opacity = 1f))
        val cropped = composited.withCrop(RectF(0.5f, 0.25f, 1f, 0.75f), angleDeg = 5f)

        val output = render(cropped)

        assertEquals(SIZE / 2, output.width)
        assertEquals(RED, output.getPixel(output.width / 2, output.height / 2))
    }

    @Test
    fun `on an outpainted canvas the shot is contained in the expanded frame`() = runTest {
        // A 40×40 source widened by a quarter on each side is a 60×40 canvas.
        val expanded = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(GREEN) }
        val outpaint = Operation.Outpaint("o", Margins(0.25f, 0f, 0.25f, 0f), png(expanded, "outpaint.png"))
        val base = EditDocument("d", opaqueSource(BLUE), listOf(outpaint), createdAt = 0L, updatedAt = 0L)
        val wide = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(RED) }

        val output = render(base.withMultiShot(listOf(shot(wide, opacity = 1f))))

        assertEquals(60, output.width)
        assertEquals(RED, output.getPixel(2, 20))
        assertEquals(RED, output.getPixel(57, 20))
    }

    @Test
    fun `an adjust before the composite touches only the background, one after touches both`() = runTest {
        val subject = transparent().apply { square(this, 0, 0, SIZE / 2, GREY) }
        val source = opaqueSource(GREY)
        val before = EditDocument("d", source, createdAt = 0L, updatedAt = 0L)
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withMultiShot(listOf(shot(subject, opacity = 1f)))
        val after = EditDocument("d", source, createdAt = 0L, updatedAt = 0L)
            .withMultiShot(listOf(shot(subject, opacity = 1f)))
            .withAdjust(AdjustKind.Exposure, 0.5f)

        val beforeOut = render(before)
        val afterOut = render(after)

        // Before: the subject is the untouched grey, the background brightened.
        assertEquals(GREY, beforeOut.getPixel(2, 2))
        assertNotEquals(GREY, beforeOut.getPixel(SIZE - 2, SIZE - 2))
        // After: both brightened alike.
        assertEquals(afterOut.getPixel(2, 2), afterOut.getPixel(SIZE - 2, SIZE - 2))
        assertNotEquals(GREY, afterOut.getPixel(2, 2))
    }

    @Test
    fun `a missing subject fails the render instead of dropping it`() = runTest {
        val document = composite(opaqueSource(BLUE), shot(solid(RED), opacity = 1f))
        val gone = document.withMultiShot(
            document.multiShot()!!.shots.map { it.copy(subjectRef = ImageRef(File(temp.root, "gone.png").path)) },
        )
        val renderer = renderer()

        assertEquals(Result.Failure(AppError.MissingSource), renderer.preview(gone, SIZE))
        assertEquals(Result.Failure(AppError.MissingSource), renderer.full(gone))
    }

    // ---- the time layout's hero (§6) ---------------------------------------------------------

    @Test
    fun `inside the hero mask the input stays exactly, colour and alpha, under every afterimage`() = runTest {
        // A translucent background with an opaque hero square; a full-frame red afterimage on top.
        val source = png(
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.argb(90, 0, 0, 255))
                square(this, HERO_LEFT, HERO_LEFT, HERO_SIZE, GREEN)
                setPixel(CENTRE, CENTRE, Color.argb(140, 200, 40, 10))
            },
            "source${files++}.png",
        )
        val input = render(EditDocument("d", source, createdAt = 0L, updatedAt = 0L))

        val output = render(timed(source, heroMask(), shot(solid(RED), opacity = 0.7f, anchor = true)))

        // Two pixels in from the mask's edge the feather has ended: the input, bit for bit.
        for (y in HERO_LEFT + 2 until HERO_LEFT + HERO_SIZE - 2) {
            for (x in HERO_LEFT + 2 until HERO_LEFT + HERO_SIZE - 2) {
                assertEquals("($x, $y)", input.getPixel(x, y), output.getPixel(x, y))
            }
        }
        // Outside it the afterimage is the usual source-over, the background's own alpha included.
        val outside = output.getPixel(2, 2)
        assertTrue(Color.alpha(outside) > 90)
        assertTrue(Color.red(outside) > 200)
        // On the edge the two are blended once: between the input and the composite.
        val edge = output.getPixel(HERO_LEFT, CENTRE)
        assertTrue(Color.red(edge) in 1 until 255)
    }

    /**
     * REVIEW R1 (2026-10-01): a soft hero edge over a transparent or translucent input. The blend
     * is on premultiplied pixels: a transparent input adds no colour, so the afterimage's red stays
     * red, and only the alpha falls with the mask. Uniform masks make every pixel one checkable case.
     */
    @Test
    fun `a soft hero edge blends premultiplied pixels over a transparent or translucent input`() = runTest {
        val inputs = listOf(Color.argb(0, 0, 0, 0), Color.argb(100, 0, 255, 0))
        val afterimages = listOf(1f, 0.5f)
        inputs.forEach { inputPixel ->
            val source = png(
                Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(inputPixel) },
                "source${files++}.png",
            )
            afterimages.forEach { opacity ->
                val composite = render(
                    timed(source, uniformHero(0), shot(solid(RED), opacity = opacity, anchor = true)),
                ).getPixel(CENTRE, CENTRE)
                listOf(0, 64, 128, 192, 255).forEach { weight ->
                    val afterimage = shot(solid(RED), opacity = opacity, anchor = true)
                    val document = timed(source, uniformHero(weight), afterimage)
                    val renderer = renderer()
                    val full = bitmap(renderer.full(document)).getPixel(CENTRE, CENTRE)
                    val preview = bitmap(renderer.preview(document, SIZE / 2)).getPixel(SIZE / 4, SIZE / 4)
                    val expected = premultipliedLerp(composite, inputPixel, weight)
                    val case = "input ${Integer.toHexString(inputPixel)}, opacity $opacity, mask $weight"
                    assertPixel(case, expected, full)
                    assertPixel("$case (preview)", expected, preview)
                    if (weight == 0) assertEquals(case, composite, full)
                    if (weight == OPAQUE) assertEquals(case, inputPixel, full)
                }
            }
        }
    }

    @Test
    fun `the free layout keeps a stored hero but does not protect with it`() = runTest {
        val source = opaqueSource(BLUE)
        val timed = timed(source, heroMask(), shot(solid(RED), opacity = 1f, anchor = true))
        val stored = timed.multiShot()!!
        val free = timed.withMultiShot(stored.shots, mode = MultiShotMode.Free, timeline = stored.timeline)

        assertEquals(RED, render(free).getPixel(CENTRE, CENTRE))
        assertEquals(BLUE, render(timed).getPixel(CENTRE, CENTRE))
    }

    @Test
    fun `an adjust before the composite reaches the hero, one after reaches everything`() = runTest {
        val source = opaqueSource(GREY)
        val before = EditDocument("d", source, createdAt = 0L, updatedAt = 0L).withAdjust(AdjustKind.Exposure, 0.5f)
        val adjusted = render(before).getPixel(CENTRE, CENTRE)
        val composite = timed(source, heroMask(), shot(solid(RED), opacity = 1f, anchor = true))

        val adjustedFirst = render(
            before.withMultiShot(
                composite.multiShot()!!.shots,
                mode = MultiShotMode.Timeline,
                timeline = composite.multiShot()!!.timeline,
            ),
        )
        val adjustedAfter = render(composite.withAdjust(AdjustKind.Exposure, 0.5f))

        assertNotEquals(GREY, adjusted)
        assertEquals("the hero is the adjusted input", adjusted, adjustedFirst.getPixel(CENTRE, CENTRE))
        assertEquals("an adjust after it is applied to the hero too", adjusted, adjustedAfter.getPixel(CENTRE, CENTRE))
    }

    @Test
    fun `the time layout draws in time order, later on top`() = runTest {
        val red = shot(solid(RED), opacity = 1f, id = "a", anchor = true)
        val green = shot(solid(GREEN), opacity = 1f, id = "b", anchor = true)
        val source = opaqueSource(BLUE)
        val hero = heroMask(empty = true)

        val greenLast = render(timed(source, hero, red, green, order = listOf("a", "b"))).getPixel(2, 2)
        val redLast = render(timed(source, hero, red, green, order = listOf("b", "a"))).getPixel(2, 2)

        assertEquals(GREEN, greenLast)
        assertEquals(RED, redLast)
    }

    @Test
    fun `five afterimages draw in time order under a protected hero`() = runTest {
        val colours = listOf(RED, GREEN, BLUE, WHITE, GREY)
        val shots = colours.mapIndexed { index, colour ->
            shot(solid(colour), opacity = 1f, id = "s$index", anchor = true)
        }
        val source = opaqueSource(BLACK)
        val hero = heroMask()
        val order = listOf("s3", "s0", "s4", "s1", "s2")

        val output = render(timed(source, hero, *shots.toTypedArray(), order = order))

        assertEquals("the newest afterimage is on top", BLUE, output.getPixel(2, 2))
        assertEquals("the hero is the input", BLACK, output.getPixel(CENTRE, CENTRE))
        val reordered = render(timed(source, hero, *shots.toTypedArray(), order = order.reversed()))
        assertEquals(WHITE, reordered.getPixel(2, 2))
    }

    @Test
    fun `a hero mask that is gone or cut for another canvas fails the render`() = runTest {
        val source = opaqueSource(BLUE)
        val document = timed(source, heroMask(), shot(solid(RED), opacity = 1f, anchor = true))
        val timeline = document.multiShot()!!.timeline!!
        val gone = document.withMultiShot(
            document.multiShot()!!.shots,
            mode = MultiShotMode.Timeline,
            timeline = timeline.copy(hero = timeline.hero!!.copy(ref = ImageRef(File(temp.root, "gone.png").path))),
        )
        val other = Bitmap.createBitmap(SIZE, SIZE / 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val wrongShape = document.withMultiShot(
            document.multiShot()!!.shots,
            mode = MultiShotMode.Timeline,
            timeline = timeline.copy(hero = HeroMask(png(other), SIZE, SIZE / 2, NormPoint(0.5f, 1f))),
        )
        val renderer = renderer()

        assertEquals(Result.Failure(AppError.MissingSource), renderer.full(gone))
        assertEquals(Result.Failure(AppError.MissingSource), renderer.preview(wrongShape, SIZE))
    }

    @Test
    fun `preview and export of a protected composite agree`() = runTest {
        val source = opaqueSource(BLUE)
        val document = timed(source, heroMask(), shot(solid(RED), opacity = 1f, anchor = true))
        val renderer = renderer()

        val preview = bitmap(renderer.preview(document, SIZE / 2))
        val full = bitmap(renderer.full(document))

        assertEquals(BLUE, preview.getPixel(SIZE / 4, SIZE / 4))
        assertEquals(BLUE, full.getPixel(CENTRE, CENTRE))
        assertEquals(RED, preview.getPixel(1, 1))
        assertEquals(RED, full.getPixel(1, 1))
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The hero square, feathered once the way 추출 완료 writes it: an opaque bitmap's alpha. */
    private fun heroMask(empty: Boolean = false): HeroMask {
        val binary = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        if (!empty) square(binary, HERO_LEFT, HERO_LEFT, HERO_SIZE, Color.BLACK)
        val opaque = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val feathered = MultiShotSubject.compose(opaque, binary)
        return HeroMask(png(feathered), SIZE, SIZE, NormPoint(0.5f, (HERO_LEFT + HERO_SIZE).toFloat() / SIZE))
    }

    /** A hero mask of one alpha everywhere — [weight] of the input comes back at every pixel. */
    private fun uniformHero(weight: Int): HeroMask {
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(weight, 0, 0, 0))
        }
        return HeroMask(png(mask), SIZE, SIZE, NormPoint(0.5f, 1f))
    }

    /** The reference: `lerp` of premultiplied colour and of alpha, then un-premultiplied. */
    private fun premultipliedLerp(from: Int, to: Int, weight: Int): Int {
        val w = weight / 255f
        val alpha = Color.alpha(from) * (1 - w) + Color.alpha(to) * w
        if (alpha == 0f) return 0
        fun channel(get: (Int) -> Int) =
            ((get(from) * Color.alpha(from) * (1 - w) + get(to) * Color.alpha(to) * w) / alpha).toInt()
        return Color.argb(Math.round(alpha), channel(Color::red), channel(Color::green), channel(Color::blue))
    }

    private fun assertPixel(message: String, expected: Int, actual: Int) {
        assertClose("$message alpha", Color.alpha(expected), Color.alpha(actual))
        // Colour is meaningless where nothing is left to see.
        if (Color.alpha(expected) < 2) return
        assertClose("$message red", Color.red(expected), Color.red(actual), tolerance = 3)
        assertClose("$message green", Color.green(expected), Color.green(actual), tolerance = 3)
        assertClose("$message blue", Color.blue(expected), Color.blue(actual), tolerance = 3)
    }

    private fun timed(
        source: ImageRef,
        hero: HeroMask,
        vararg shots: Shot,
        order: List<String> = shots.map { it.id },
    ): EditDocument = EditDocument("d", source, createdAt = 0L, updatedAt = 0L).withMultiShot(
        shots.toList(),
        mode = MultiShotMode.Timeline,
        timeline = Timeline(order, TimelineLayout(), hero),
    )

    private var files = 0

    private fun png(bitmap: Bitmap, name: String = "f${files++}.png"): ImageRef {
        val file = File(temp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private fun opaqueSource(color: Int): ImageRef = png(
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        "source${files++}.png",
    )

    private fun translucentSource(color: Int, alpha: Int): ImageRef = png(
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        },
        "source${files++}.png",
    )

    private fun solid(color: Int, alpha: Int = OPAQUE): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        }

    private fun transparent(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private fun square(bitmap: Bitmap, left: Int, top: Int, size: Int, color: Int) {
        for (y in top until top + size) for (x in left until left + size) bitmap.setPixel(x, y, color)
    }

    @Suppress("LongParameterList")
    private fun shot(
        subject: Bitmap,
        opacity: Float,
        id: String = "s${files}",
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scale: Float = 1f,
        rotationDeg: Float = 0f,
        anchor: Boolean = false,
    ) = Shot(
        id = id,
        subjectRef = png(subject),
        widthPx = subject.width,
        heightPx = subject.height,
        placement = ShotPlacement(offsetX, offsetY, scale, rotationDeg, opacity),
        anchor = NormPoint(0.5f, 1f).takeIf { anchor },
    )

    private fun composite(source: ImageRef, vararg shots: Shot): EditDocument =
        EditDocument("d", source, createdAt = 0L, updatedAt = 0L).withMultiShot(shots.toList())

    private suspend fun TestScope.render(document: EditDocument): Bitmap = bitmap(renderer().full(document))

    private fun redCentroid(bitmap: Bitmap): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 128 && Color.blue(pixel) < 128) {
                    sumX += x + 0.5f
                    sumY += y + 0.5f
                    count++
                }
            }
        }
        assertTrue("no subject pixels", count > 0)
        return sumX / count / bitmap.width to sumY / count / bitmap.height
    }

    private fun assertClose(message: String, expected: Int, actual: Int, tolerance: Int = 2) {
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
        const val SIZE = 40
        const val CENTRE = 20
        const val OPAQUE = 255
        const val HALF = 0.5f
        const val HERO_LEFT = 12
        const val HERO_SIZE = 16
        val RED = Color.rgb(255, 0, 0)
        val GREEN = Color.rgb(0, 255, 0)
        val BLUE = Color.rgb(0, 0, 255)
        val WHITE = Color.rgb(255, 255, 255)
        val BLACK = Color.rgb(0, 0, 0)
        val GREY = Color.rgb(100, 100, 100)
    }
}
