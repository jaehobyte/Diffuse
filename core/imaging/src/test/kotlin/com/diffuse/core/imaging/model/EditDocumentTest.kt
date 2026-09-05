package com.diffuse.core.imaging.model

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** RectF needs the framework, so these run on Robolectric rather than as plain JVM tests. */
@RunWith(AndroidJUnit4::class)
class EditDocumentTest {

    private val document = EditDocument(
        id = "doc",
        source = ImageRef("/tmp/photo.jpg"),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    @Test
    fun `an empty operation list is valid`() {
        assertTrue(document.operations.isEmpty())
        assertEquals(0f, document.adjustValue(AdjustKind.Exposure), 0f)
        assertNull(document.crop())
    }

    @Test
    fun `setting a kind twice updates in place and keeps its position`() {
        val edited = document
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, 0.2f)
            .withAdjust(AdjustKind.Exposure, -0.3f)

        assertEquals(2, edited.operations.size)
        assertEquals(AdjustKind.Exposure, (edited.operations[0] as Operation.Adjust).kind)
        assertEquals(-0.3f, edited.adjustValue(AdjustKind.Exposure), 0.0001f)
        assertEquals(0.2f, edited.adjustValue(AdjustKind.Contrast), 0.0001f)
    }

    @Test
    fun `a neutral value removes the entry instead of storing a no-op`() {
        val edited = document
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Exposure, 0f)

        assertTrue(edited.operations.isEmpty())
    }

    @Test
    fun `values are coerced into the range for their kind`() {
        val edited = document
            .withAdjust(AdjustKind.Exposure, 5f)
            .withAdjust(AdjustKind.Sharpen, -2f)

        assertEquals(1f, edited.adjustValue(AdjustKind.Exposure), 0f)
        // Sharpen is 0..1, so -2 coerces to 0, which is neutral and therefore dropped.
        assertEquals(0f, edited.adjustValue(AdjustKind.Sharpen), 0f)
        assertEquals(1, edited.operations.size)
    }

    @Test
    fun `a new crop replaces the old one in place`() {
        val edited = document
            .withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withCrop(RectF(0.2f, 0.2f, 0.8f, 0.8f), 15f)

        assertEquals(2, edited.operations.size)
        assertTrue(edited.operations[0] is Operation.Crop)
        assertEquals(15f, edited.crop()!!.angleDeg, 0f)
        assertEquals(0.2f, edited.crop()!!.rect.left, 0f)
    }

    @Test
    fun `a full frame unrotated crop is dropped like a neutral adjust`() {
        val edited = document
            .withCrop(RectF(0.2f, 0.2f, 0.8f, 0.8f), 15f)
            .withCrop(RectF(0f, 0f, 1f, 1f), 0f)

        assertNull(edited.crop())
        assertTrue(edited.operations.isEmpty())
    }
}
