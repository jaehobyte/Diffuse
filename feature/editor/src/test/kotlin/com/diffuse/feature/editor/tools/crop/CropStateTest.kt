package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropStateTest {

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
    private val imageAspect = 4f / 3f
    private val imageRect = Rect(0f, 0f, 400f, 300f)

    @Test
    fun `rotating 90 degrees twice then applying stores 180`() {
        val state = CropState().rotated(1).rotated(1)

        val applied = state.applyTo(document.withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f))

        assertEquals(180f, applied.crop()!!.angleDeg, 0.001f)
    }

    @Test
    fun `rotation wraps at four quarter turns`() {
        assertEquals(0f, CropState().rotated(4).angleDeg, 0.001f)
        assertEquals(270f, CropState().rotated(-1).angleDeg, 0.001f)
    }

    @Test
    fun `angle combines quarter turns and straighten`() {
        val state = CropState().rotated(1).straightened(15f)

        assertEquals(105f, state.angleDeg, 0.001f)
    }

    @Test
    fun `straightening shrinks the rect so it stays inside`() {
        val state = CropState(sourceAspect = imageAspect).straightened(20f)

        assertTrue(
            "rect escaped: ${state.rect}",
            CropGeometry.contains(state.rect, 20f, imageAspect),
        )
        assertTrue("expected a shrink", state.rect.width() < 1f)
    }

    @Test
    fun `re-opening the tool restores the stored crop`() {
        val stored = document.withCrop(RectF(0.2f, 0.25f, 0.8f, 0.75f), 105f)

        val state = CropState.from(stored, sourceAspect = 4f / 3f)

        assertEquals(1, state.quarterTurns)
        assertEquals(15f, state.straightenDeg, 0.001f)
        assertEquals(0.2f, state.rect.left, 0.001f)
    }

    @Test
    fun `a full frame apply removes the crop`() {
        val applied = CropState().applyTo(document.withCrop(RectF(0.2f, 0.2f, 0.8f, 0.8f), 0f))

        assertNull(applied.crop())
    }

    @Test
    fun `dragging a corner past the minimum clamps instead of inverting`() {
        val rect = RectF(0.2f, 0.2f, 0.8f, 0.8f)

        val dragged = CropDrag.apply(
            rect = rect,
            grab = CropGrab.TopLeft,
            dragAmount = Offset(1000f, 1000f),
            imageRect = imageRect,
            aspect = AspectPreset.Free,
        )

        assertEquals(CropGeometry.MIN_FRACTION, dragged.width(), 1e-3f)
        assertEquals(CropGeometry.MIN_FRACTION, dragged.height(), 1e-3f)
    }

    @Test
    fun `a drag starting outside the rect is not grabbed, so the canvas pans`() {
        val rect = RectF(0.4f, 0.4f, 0.6f, 0.6f)

        val grab = CropDrag.grabAt(Offset(10f, 10f), rect, imageRect, handlePx = 24f)

        assertNull(grab)
    }

    @Test
    fun `dragging inside moves the rect and stops at the edge`() {
        val rect = RectF(0.4f, 0.4f, 0.6f, 0.6f)

        val moved = CropDrag.apply(
            rect = rect,
            grab = CropGrab.Inside,
            dragAmount = Offset(1000f, 0f),
            imageRect = imageRect,
            aspect = AspectPreset.Free,
        )

        assertEquals(1f, moved.right, 1e-3f)
        assertEquals(rect.width(), moved.width(), 1e-3f)
    }
}
