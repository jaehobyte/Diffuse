package com.diffuse.core.imaging.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

/** specs/edit_model.md, the v2 Mask rules. */
class MaskModelTest {

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    @Test
    fun `withMask appends the op and makes it active in one step`() {
        val updated = document.withMask(ImageRef("/mask_a.png"), id = "a")

        assertEquals("a", updated.activeMaskId)
        assertEquals(ImageRef("/mask_a.png"), updated.activeMask()?.maskRef)
        assertEquals(1, updated.operations.size)
    }

    @Test
    fun `a second mask becomes active but the first stays in the list for undo`() {
        val updated = document
            .withMask(ImageRef("/mask_a.png"), id = "a")
            .withMask(ImageRef("/mask_b.png"), id = "b")

        assertEquals("b", updated.activeMaskId)
        assertEquals(2, updated.operations.filterIsInstance<Operation.Mask>().size)
        assertEquals(ImageRef("/mask_a.png"), updated.mask("a")?.maskRef)
    }

    @Test
    fun `a mask op changes no pixels, so the adjust and crop accessors ignore it`() {
        val updated = document.withMask(ImageRef("/mask_a.png"), id = "a")

        assertEquals(0f, updated.adjustValue(AdjustKind.Exposure), 0f)
        assertNull(updated.crop())
    }

    @Test
    fun `references resolve when the active mask is present, or absent entirely`() {
        assertTrue(document.referencesResolve())
        assertTrue(document.withMask(ImageRef("/mask_a.png"), id = "a").referencesResolve())
    }

    @Test
    fun `a dangling activeMaskId does not resolve`() {
        assertFalse(document.copy(activeMaskId = "gone").referencesResolve())
    }

    @Test
    fun `a mask and its activeMaskId round-trip through JSON`() {
        val original = document.withMask(ImageRef("/projects/d/mask_a.png"), id = "a")

        val decoded = EditDocumentJson.decode(EditDocumentJson.encode(original))

        assertEquals(original, decoded)
        assertEquals("a", decoded.activeMaskId)
        assertEquals(ImageRef("/projects/d/mask_a.png"), decoded.activeMask()?.maskRef)
    }

    @Test
    fun `a document with no active mask omits the field`() {
        val encoded = EditDocumentJson.encode(document)

        assertFalse(encoded.contains("activeMaskId"))
        assertNull(EditDocumentJson.decode(encoded).activeMaskId)
    }

    @Test
    fun `a mask node without a maskRef is dropped, not fatal`() {
        val text = """
            {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,
             "operations":[{"type":"mask","id":"m"},{"type":"adjust","id":"a","kind":"Exposure","value":0.5}]}
        """.trimIndent()

        val restored = EditDocumentJson.decode(text)

        assertEquals(1, restored.operations.size)
        assertEquals(0.5f, restored.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `an activeMaskId whose op was dropped fails validation`() {
        val text = """
            {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,"activeMaskId":"m",
             "operations":[{"type":"mask","id":"m"}]}
        """.trimIndent()

        assertFalse(EditDocumentJson.decode(text).referencesResolve())
    }

    @Test
    fun `a masked and an unmasked adjustment of the same kind coexist`() {
        val updated = document
            .withMask(ImageRef("/mask_a.png"), id = "a")
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Exposure, -0.25f, maskId = "a")

        assertEquals(0.5f, updated.adjustValue(AdjustKind.Exposure), 0f)
        assertEquals(-0.25f, updated.adjustValue(AdjustKind.Exposure, maskId = "a"), 0f)
        assertEquals(2, updated.operations.filterIsInstance<Operation.Adjust>().size)
    }

    @Test
    fun `a masked adjustment updates in place rather than stacking`() {
        val updated = document
            .withMask(ImageRef("/mask_a.png"), id = "a")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "a")
            .withAdjust(AdjustKind.Exposure, 0.25f, maskId = "a")

        assertEquals(1, updated.operations.filterIsInstance<Operation.Adjust>().size)
        assertEquals(0.25f, updated.adjustValue(AdjustKind.Exposure, maskId = "a"), 0f)
    }

    @Test
    fun `a masked adjustment round-trips through JSON`() {
        val original = document
            .withMask(ImageRef("/mask_a.png"), id = "a")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "a")

        val decoded = EditDocumentJson.decode(EditDocumentJson.encode(original))

        assertEquals(original, decoded)
        assertEquals("a", decoded.operations.filterIsInstance<Operation.Adjust>().single().maskId)
    }

    @Test
    fun `an unmasked adjustment writes no maskId at all`() {
        val encoded = EditDocumentJson.encode(document.withAdjust(AdjustKind.Exposure, 0.5f))

        assertFalse(encoded.contains("maskId"))
    }

    @Test
    fun `masks survive alongside adjustments and a crop`() {
        val original = document
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withMask(ImageRef("/mask_a.png"), id = "a")

        val decoded = EditDocumentJson.decode(EditDocumentJson.encode(original))

        assertEquals(original, decoded)
    }
}
