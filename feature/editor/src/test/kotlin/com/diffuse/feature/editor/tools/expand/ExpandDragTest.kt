package com.diffuse.feature.editor.tools.expand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.diffuse.core.imaging.model.MAX_MARGIN_FRACTION
import com.diffuse.core.imaging.model.Margins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** specs/outpaint.md §6, §8: the drag rules, before any gesture is involved. */
class ExpandDragTest {

    @Test
    fun `an outward drag grows that side only`() {
        val grown = ExpandDrag.apply(Margins.None, ExpandEdge.Left, Offset(-50f, 0f), INTERIOR)

        assertEquals(0.5f, grown.left, TOLERANCE)
        assertEquals(Margins.None.copy(left = grown.left), grown)
    }

    @Test
    fun `each edge moves its own margin, in its own direction`() {
        val interior = INTERIOR
        assertEquals(
            0.25f,
            ExpandDrag.apply(Margins.None, ExpandEdge.Right, Offset(25f, 0f), interior).right,
            TOLERANCE,
        )
        assertEquals(
            0.25f,
            ExpandDrag.apply(Margins.None, ExpandEdge.Top, Offset(0f, -25f), interior).top,
            TOLERANCE,
        )
        assertEquals(
            0.25f,
            ExpandDrag.apply(Margins.None, ExpandEdge.Bottom, Offset(0f, 25f), interior).bottom,
            TOLERANCE,
        )
    }

    /** §6: shrinking is 자르기's job, and the two tools do not overlap. */
    @Test
    fun `an inward drag clamps at zero rather than shrinking the photo`() {
        val margins = Margins(left = 0.1f)

        val dragged = ExpandDrag.apply(margins, ExpandEdge.Left, Offset(100f, 0f), INTERIOR)

        assertEquals(0f, dragged.left, 0f)
    }

    /** §6: dragging past the maximum stops at it. No rubber-band, no snap. */
    @Test
    fun `a drag past the maximum stops at it`() {
        val dragged = ExpandDrag.apply(Margins.None, ExpandEdge.Right, Offset(500f, 0f), INTERIOR)

        assertEquals(MAX_MARGIN_FRACTION, dragged.right, 0f)
    }

    @Test
    fun `a drag on a collapsed interior changes nothing`() {
        val margins = Margins(left = 0.1f)

        val dragged = ExpandDrag.apply(margins, ExpandEdge.Left, Offset(-50f, 0f), Rect.Zero)

        assertEquals(margins, dragged)
    }

    // ---- grabbing --------------------------------------------------------

    @Test
    fun `each handle is grabbed at the midpoint of its edge`() {
        val frame = Rect(0f, 0f, 200f, 100f)

        assertEquals(ExpandEdge.Left, ExpandDrag.grabAt(Offset(0f, 50f), frame, RADIUS))
        assertEquals(ExpandEdge.Top, ExpandDrag.grabAt(Offset(100f, 0f), frame, RADIUS))
        assertEquals(ExpandEdge.Right, ExpandDrag.grabAt(Offset(200f, 50f), frame, RADIUS))
        assertEquals(ExpandEdge.Bottom, ExpandDrag.grabAt(Offset(100f, 100f), frame, RADIUS))
    }

    /** §6: a drag outside a handle pans the canvas, exactly as the crop overlay behaves. */
    @Test
    fun `a touch away from every handle is left for the canvas`() {
        val frame = Rect(0f, 0f, 200f, 100f)

        assertNull(ExpandDrag.grabAt(Offset(100f, 50f), frame, RADIUS))
        assertNull(ExpandDrag.grabAt(Offset(0f, 0f), frame, RADIUS))
    }

    // ---- the interior ----------------------------------------------------

    @Test
    fun `with no margins the interior is the frame`() {
        val frame = Rect(10f, 20f, 210f, 120f)

        assertEquals(frame, ExpandDrag.interiorOf(frame, Margins.None))
    }

    /**
     * The frame is what the canvas fits, so the photo inside it is smaller by exactly the
     * margins — which is what makes a drag's fraction measure against the photo, not the frame.
     */
    @Test
    fun `the interior is the frame divided by the margins it carries`() {
        val frame = Rect(0f, 0f, 200f, 150f)
        val margins = Margins(left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0f)

        val interior = ExpandDrag.interiorOf(frame, margins)

        assertEquals(100f, interior.width, TOLERANCE)
        assertEquals(100f, interior.height, TOLERANCE)
        assertEquals(50f, interior.left, TOLERANCE)
        assertEquals(50f, interior.top, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
        const val RADIUS = 24f
        val INTERIOR = Rect(0f, 0f, 100f, 100f)
    }
}
