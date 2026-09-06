package com.diffuse.core.imaging.model

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditDocumentJsonTest {

    private fun document(operations: List<Operation> = emptyList()) = EditDocument(
        id = "doc-1",
        source = ImageRef("/data/app/photo.jpg"),
        operations = operations,
        createdAt = 111L,
        updatedAt = 222L,
    )

    @Test
    fun `round trips every AdjustKind and a Crop`() {
        val operations = AdjustKind.entries.mapIndexed { index, kind ->
            Operation.Adjust("adjust-$index", kind, kind.coerce(0.5f))
        } + Operation.Crop("crop-1", RectF(0.1f, 0.2f, 0.8f, 0.9f), 15f)

        val restored = EditDocumentJson.decode(EditDocumentJson.encode(document(operations)))

        assertEquals(document(operations), restored)
    }

    @Test
    fun `round trips a document with no operations`() {
        val restored = EditDocumentJson.decode(EditDocumentJson.encode(document()))

        assertEquals(document(), restored)
    }

    @Test
    fun `an unknown operation type is dropped and the document still loads`() {
        val text = """
            {"v":1,"id":"doc-1","source":"/p.jpg","createdAt":111,"updatedAt":222,
             "operations":[
               {"type":"adjust","id":"a","kind":"Exposure","value":0.5},
               {"type":"mask","id":"m","brush":"soft"},
               {"type":"crop","id":"c","angleDeg":0.0,"left":0.1,"top":0.1,"right":0.9,"bottom":0.9}
             ]}
        """.trimIndent()

        val restored = EditDocumentJson.decode(text)

        assertEquals(2, restored.operations.size)
        assertEquals(0.5f, restored.adjustValue(AdjustKind.Exposure), 0f)
        assertEquals(0.1f, restored.crop()!!.rect.left, 0.0001f)
    }

    @Test
    fun `an unknown AdjustKind is dropped and the document still loads`() {
        val text = """
            {"v":1,"id":"doc-1","source":"/p.jpg","createdAt":111,"updatedAt":222,
             "operations":[
               {"type":"adjust","id":"a","kind":"Clarity","value":0.5},
               {"type":"adjust","id":"b","kind":"Contrast","value":0.25}
             ]}
        """.trimIndent()

        val restored = EditDocumentJson.decode(text)

        assertEquals(1, restored.operations.size)
        assertEquals(0.25f, restored.adjustValue(AdjustKind.Contrast), 0f)
        assertEquals(0f, restored.adjustValue(AdjustKind.Exposure), 0f)
    }

    @Test
    fun `a masked HSL adjust round trips`() {
        // specs/adjust_hsl.md §3: 24 more kinds, and no serializer change to carry them.
        val kind = AdjustKind.entries.first { it.hsl?.band == HslBand.Blue }
        val operations = listOf(
            Operation.Mask("m", ImageRef("/projects/d/mask.png")),
            Operation.Adjust("a", kind, 0.5f, maskId = "m"),
        )

        val restored = EditDocumentJson.decode(EditDocumentJson.encode(document(operations)))

        assertEquals(operations, restored.operations)
    }

    @Test
    fun `the encoded root carries the schema version`() {
        assertTrue(EditDocumentJson.encode(document()).contains("\"v\":1"))
    }

    @Test
    fun `dropping never leaves a partial operation behind`() {
        val text = """
            {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,
             "operations":[{"type":"unknown"}]}
        """.trimIndent()

        val restored = EditDocumentJson.decode(text)

        assertTrue(restored.operations.isEmpty())
        assertNull(restored.crop())
    }
}
