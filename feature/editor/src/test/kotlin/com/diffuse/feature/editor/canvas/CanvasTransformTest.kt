package com.diffuse.feature.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** specs/canvas.md: screen ↔ image conversion at fit, at 2×, and with an offset. */
class CanvasTransformTest {

    private val bounds = CanvasBounds(canvas = Size(1000f, 800f), image = Size(400f, 200f))
    private val canvas = bounds.canvas
    private val image = bounds.image
    private val marginPx = 16f

    private fun viewportAtFit(): CanvasViewport {
        val fit = CanvasMath.fitScale(bounds, marginPx)
        return CanvasViewport(scale = fit, offset = Offset.Zero, fitScale = fit)
    }

    private fun transformFor(viewport: CanvasViewport) = CanvasTransform(
        imageRect = CanvasMath.imageRect(bounds, viewport),
        scale = viewport.scale,
    )

    @Test
    fun `fit scale respects the 16dp margin on the limiting axis`() {
        // 400x200 into 968x768 available: width is limiting at 968/400 = 2.42.
        assertEquals(2.42f, CanvasMath.fitScale(bounds, marginPx), 0.001f)
    }

    @Test
    fun `at fit the image centre maps to the canvas centre`() {
        val transform = transformFor(viewportAtFit())

        val centre = transform.imageToScreen(Offset(image.width / 2f, image.height / 2f))

        assertEquals(canvas.width / 2f, centre.x, 0.01f)
        assertEquals(canvas.height / 2f, centre.y, 0.01f)
    }

    @Test
    fun `screen to image round trips at fit`() {
        val transform = transformFor(viewportAtFit())
        val point = Offset(123f, 77f)

        val roundTripped = transform.imageToScreen(transform.screenToImage(point))

        assertEquals(point.x, roundTripped.x, 0.01f)
        assertEquals(point.y, roundTripped.y, 0.01f)
    }

    @Test
    fun `screen to image round trips at 2x with an offset`() {
        val fit = CanvasMath.fitScale(bounds, marginPx)
        val viewport = CanvasViewport(
            scale = fit * 2f,
            offset = Offset(-40f, 25f),
            fitScale = fit,
        )
        val transform = transformFor(viewport)
        val point = Offset(600f, 400f)

        val imagePoint = transform.screenToImage(point)
        val roundTripped = transform.imageToScreen(imagePoint)

        assertEquals(point.x, roundTripped.x, 0.01f)
        assertEquals(point.y, roundTripped.y, 0.01f)
        // The offset shifts the image rect, so the same screen point maps further left.
        assertEquals((point.x - transform.imageRect.left) / viewport.scale, imagePoint.x, 0.01f)
    }

    /** tasks.md T24: the live rotation is a canvas transform, so it is asserted here. */
    @Test
    fun `a straighten rotates in place and leaves the fitted size alone`() {
        val transform = OverlayTransform(straightenDeg = 15f)

        assertEquals(15f, transform.angleDeg, 0.001f)
        assertEquals(image, transform.turnedSize(image))
        val rect = CanvasMath.imageRect(bounds, viewportAtFit())
        assertEquals(rect, transform.drawRect(rect))
    }

    @Test
    fun `a quarter turn swaps the fitted size and the drawn rect about the centre`() {
        val transform = OverlayTransform(quarterTurns = 1)

        assertEquals(90f, transform.angleDeg, 0.001f)
        assertEquals(Size(image.height, image.width), transform.turnedSize(image))

        // The canvas fits the turned image, then draws the bitmap into a rect that lands
        // on it once rotated: swapped extents, same centre.
        val turnedBounds = CanvasBounds(canvas, transform.turnedSize(image))
        val fit = CanvasMath.fitScale(turnedBounds, marginPx)
        val rect = CanvasMath.imageRect(
            turnedBounds,
            CanvasViewport(scale = fit, offset = Offset.Zero, fitScale = fit),
        )
        val drawn = transform.drawRect(rect)

        assertEquals(rect.height, drawn.width, 0.01f)
        assertEquals(rect.width, drawn.height, 0.01f)
        assertEquals(rect.center.x, drawn.center.x, 0.01f)
        assertEquals(rect.center.y, drawn.center.y, 0.01f)
    }

    @Test
    fun `the top left image pixel sits at the image rect origin`() {
        val transform = transformFor(viewportAtFit())

        val origin = transform.imageToScreen(Offset.Zero)

        assertEquals(transform.imageRect.left, origin.x, 0.01f)
        assertEquals(transform.imageRect.top, origin.y, 0.01f)
    }
}
