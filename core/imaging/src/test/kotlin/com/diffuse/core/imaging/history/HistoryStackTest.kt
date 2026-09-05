package com.diffuse.core.imaging.history

import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** No crops here, so no RectF and no Robolectric: these stay plain JVM tests. */
class HistoryStackTest {

    private val base = EditDocument(
        id = "doc",
        source = ImageRef("/p.jpg"),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun exposure(value: Float) = base.withAdjust(AdjustKind.Exposure, value)

    @Test
    fun `a fresh stack can neither undo nor redo`() {
        val history = HistoryStack(base)

        assertEquals(base, history.current.value)
        assertFalse(history.canUndo.value)
        assertFalse(history.canRedo.value)
    }

    @Test
    fun `undo and redo walk the stack in order`() {
        val history = HistoryStack(base)
        history.push(exposure(0.1f))
        history.commitCoalesce()
        history.push(exposure(0.2f))

        assertEquals(0.2f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
        history.undo()
        assertEquals(0.1f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
        history.undo()
        assertEquals(base, history.current.value)
        assertFalse(history.canUndo.value)
        assertTrue(history.canRedo.value)

        history.redo()
        assertEquals(0.1f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `undo at the bottom and redo at the top are no-ops`() {
        val history = HistoryStack(base)

        history.undo()
        assertEquals(base, history.current.value)

        history.push(exposure(0.5f))
        history.redo()
        assertEquals(0.5f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `a new push clears the redo stack`() {
        val history = HistoryStack(base)
        history.push(exposure(0.1f))
        history.commitCoalesce()
        history.push(exposure(0.2f))
        history.undo()
        assertTrue(history.canRedo.value)

        history.push(exposure(0.9f), coalesceKey = "adjust:Exposure")

        assertFalse(history.canRedo.value)
        assertEquals(0.9f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `a coalesced drag collapses into one undo step`() {
        val history = HistoryStack(base)
        val key = "adjust:Exposure"

        history.push(exposure(0.1f), key)
        history.push(exposure(0.2f), key)
        history.push(exposure(0.3f), key)

        assertEquals(0.3f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
        history.undo()
        assertEquals(base, history.current.value)
    }

    @Test
    fun `commitCoalesce ends the group so the next push appends`() {
        val history = HistoryStack(base)
        val key = "adjust:Exposure"

        history.push(exposure(0.1f), key)
        history.push(exposure(0.2f), key)
        history.commitCoalesce()
        history.push(exposure(0.4f), key)

        history.undo()
        assertEquals(0.2f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `a different coalesce key implicitly commits the previous group`() {
        val history = HistoryStack(base)

        history.push(exposure(0.2f), "adjust:Exposure")
        history.push(base.withAdjust(AdjustKind.Contrast, 0.3f), "adjust:Contrast")

        history.undo()
        assertEquals(0.2f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `the cap drops the oldest entry and undo stays available`() {
        val history = HistoryStack(base, maxEntries = 5)

        repeat(10) { step ->
            history.push(exposure((step + 1) / 10f))
            history.commitCoalesce()
        }

        // Five entries survive (0.6 .. 1.0); undoing four reaches the oldest, not `base`.
        repeat(4) { history.undo() }
        assertFalse(history.canUndo.value)
        assertEquals(0.6f, history.current.value.adjustValue(AdjustKind.Exposure), 0.0001f)
    }
}
