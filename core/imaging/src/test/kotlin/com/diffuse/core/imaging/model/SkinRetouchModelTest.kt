package com.diffuse.core.imaging.model

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** specs/skin_retouch_pipeline.md §4 and §6: the op, its settings, its place and its codec. */
@RunWith(AndroidJUnit4::class)
class SkinRetouchModelTest {

    private val document = EditDocument(
        id = "doc",
        source = ImageRef("/p/source.jpg"),
        createdAt = 1L,
        updatedAt = 2L,
    )

    // ---- settings --------------------------------------------------------

    @Test
    fun `settings with one active kind and its engine are valid`() {
        assertTrue(settings().isValid)
    }

    @Test
    fun `settings are invalid without every kind, finite 0 to 1, an active kind or its engine`() {
        val valid = settings()
        assertFalse(valid.copy(strengths = valid.strengths - RetouchKind.Shine).isValid)
        assertFalse(valid.copy(strengths = valid.strengths + (RetouchKind.Blemish to Float.NaN)).isValid)
        assertFalse(valid.copy(strengths = valid.strengths + (RetouchKind.Blemish to 1.5f)).isValid)
        assertFalse(valid.copy(strengths = valid.strengths + (RetouchKind.Shine to -0.1f)).isValid)
        assertFalse(valid.copy(strengths = RetouchKind.entries.associateWith { 0f }).isValid)
        assertFalse(valid.copy(engines = emptyMap()).isValid)
        assertFalse(valid.copy(engines = mapOf(RetouchKind.Blemish to " ")).isValid)
        assertFalse(valid.copy(compositeVersion = 2).isValid)
    }

    // ---- document --------------------------------------------------------

    @Test
    fun `the retouch goes before the first Adjust as one change and keeps the active mask`() {
        val entry = document
            .withMask(ImageRef("/p/mask_sel.png"), id = "sel")
            .withAdjust(AdjustKind.Exposure, 0.2f)
            .withAdjust(AdjustKind.Contrast, 0.1f)
        val index = entry.skinRetouchInsertIndex()

        val retouched = entry.withSkinRetouch(
            maskRef = ImageRef("/p/mask_s.png"),
            resultRef = ImageRef("/p/retouch_r.png"),
            settings = settings(),
            maskId = "s",
            id = "r",
            insertIndex = index,
        )

        assertEquals(1, index)
        assertEquals(entry.operations.size + 2, retouched.operations.size)
        assertEquals(Operation.Mask("s", ImageRef("/p/mask_s.png")), retouched.operations[1])
        assertEquals("r", retouched.operations[2].id)
        assertEquals(entry.operations.drop(1), retouched.operations.drop(3))
        assertEquals("sel", retouched.activeMaskId)
        assertEquals(entry.source, retouched.source)
        assertTrue(retouched.referencesResolve())
    }

    @Test
    fun `without an Adjust the retouch goes last`() {
        val entry = document.withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)

        val retouched = entry.withSkinRetouch(
            ImageRef("/p/mask_s.png"),
            ImageRef("/p/retouch_r.png"),
            settings(),
            maskId = "s",
            id = "r",
            insertIndex = entry.skinRetouchInsertIndex(),
        )

        assertEquals(1, entry.skinRetouchInsertIndex())
        assertTrue(retouched.operations[1] is Operation.Mask)
        assertTrue(retouched.operations.last() is Operation.SkinRetouch)
        assertEquals(null, retouched.activeMaskId)
    }

    @Test
    fun `the base drops the Crop and every later op but keeps all masks`() {
        val entry = document
            .withMask(ImageRef("/p/mask_a.png"), id = "a")
            .withGenerativeErase("a", ImageRef("/p/erase_e.png"), id = "e")
            .withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)
            .withAdjust(AdjustKind.Exposure, 0.2f)
            .withMask(ImageRef("/p/mask_b.png"), id = "b")
            .withGenerativeFill("b", ImageRef("/p/fill_f.png"), "sky", id = "f")

        val base = entry.skinRetouchBase(entry.skinRetouchInsertIndex())

        assertEquals(listOf("a", "e", "b"), base.operations.map { it.id })
        assertEquals("b", base.activeMaskId)
        assertTrue(base.referencesResolve())
    }

    @Test
    fun `an Outpaint stays first when the insert index would move it`() {
        val entry = document.withOutpaint(Margins(0.1f, 0f, 0.1f, 0f), ImageRef("/p/outpaint_o.png"))

        val retouched = entry.withSkinRetouch(
            ImageRef("/p/mask_s.png"),
            ImageRef("/p/retouch_r.png"),
            settings(),
            insertIndex = 0,
        )

        assertTrue(retouched.operations.first() is Operation.Outpaint)
        assertTrue(entry.skinRetouchBase(entry.skinRetouchInsertIndex()).operations.single() is Operation.Outpaint)
    }

    @Test
    fun `a retouch whose mask is missing or whose settings are invalid does not resolve`() {
        val missingMask = document.copy(
            operations = listOf(Operation.SkinRetouch("r", "gone", ImageRef("/p/retouch_r.png"), settings())),
        )
        val invalid = document.withSkinRetouch(
            ImageRef("/p/mask_s.png"),
            ImageRef("/p/retouch_r.png"),
            settings().copy(compositeVersion = 0),
        )

        assertFalse(missingMask.referencesResolve())
        assertFalse(invalid.referencesResolve())
    }

    @Test
    fun `a retouch blocks outpaint`() {
        val retouched = document.withSkinRetouch(
            ImageRef("/p/mask_s.png"),
            ImageRef("/p/retouch_r.png"),
            settings(),
        )
        // The Mask alone already blocks it; the retouch must block it on its own as well.
        val retouchOnly = retouched.copy(operations = retouched.skinRetouches())

        assertFalse(retouched.canOutpaint)
        assertFalse(retouchOnly.canOutpaint)
        assertEquals(retouchOnly, retouchOnly.withOutpaint(Margins(0.1f, 0f, 0f, 0f), ImageRef("/o.png")))
    }

    // ---- JSON ------------------------------------------------------------

    @Test
    fun `it round-trips through JSON`() {
        val original = document
            .withAdjust(AdjustKind.Exposure, 0.3f)
            .withSkinRetouch(
                ImageRef("/p/mask_s.png"),
                ImageRef("/p/retouch_r.png"),
                settings(
                    RetouchKind.Blemish to 0.5f,
                    RetouchKind.DarkCircles to 0.25f,
                ),
                maskId = "s",
                id = "r",
                insertIndex = 0,
            )

        val text = EditDocumentJson.encode(original)
        val decoded = EditDocumentJson.decode(text)

        assertEquals(original, decoded)
        assertTrue(text.contains("\"type\":\"skinRetouch\""))
        assertTrue(
            text.contains("\"strengths\":{\"blemish\":0.5,\"shine\":0.0,\"darkCircles\":0.25,\"shavingShadow\":0.0}"),
        )
        assertTrue(text.contains("\"compositeVersion\":1"))
        assertTrue(decoded.referencesResolve())
    }

    @Test
    fun `a node without its maskId or resultRef is dropped`() {
        val text = json(
            """{"type":"skinRetouch","id":"r","resultRef":"/p/retouch_r.png",""" +
                STRENGTHS + ""","engines":{"blemish":"e1"},"compositeVersion":1}""",
        )

        assertEquals(listOf("s"), EditDocumentJson.decode(text).operations.map { it.id })
    }

    @Test
    fun `invalid strengths are kept as parsed so the document does not resolve`() {
        listOf(
            """"strengths":{"blemish":1.5,"shine":0,"darkCircles":0,"shavingShadow":0}""",
            """"strengths":{"blemish":"NaN","shine":0,"darkCircles":0,"shavingShadow":0}""",
            """"strengths":{"blemish":0.5,"shine":0,"darkCircles":0}""",
            """"strengths":{"blemish":"high","shine":0,"darkCircles":0,"shavingShadow":0}""",
        ).forEach { strengths ->
            val text = json(
                """{"type":"skinRetouch","id":"r","maskId":"s","resultRef":"/p/retouch_r.png",""" +
                    strengths + ""","engines":{"blemish":"e1"},"compositeVersion":1}""",
            )

            val decoded = EditDocumentJson.decode(text)

            assertEquals(strengths, 1, decoded.skinRetouches().size)
            assertFalse(strengths, decoded.referencesResolve())
        }
    }

    @Test
    fun `a missing composite version or engine does not resolve`() {
        val noVersion = json(
            """{"type":"skinRetouch","id":"r","maskId":"s","resultRef":"/p/retouch_r.png",""" +
                STRENGTHS + ""","engines":{"blemish":"e1"}}""",
        )
        val noEngine = json(
            """{"type":"skinRetouch","id":"r","maskId":"s","resultRef":"/p/retouch_r.png",""" +
                STRENGTHS + ""","compositeVersion":1}""",
        )

        assertFalse(EditDocumentJson.decode(noVersion).referencesResolve())
        assertFalse(EditDocumentJson.decode(noEngine).referencesResolve())
    }

    private fun json(retouch: String) = """
        {"v":1,"id":"d","source":"/p.jpg","createdAt":1,"updatedAt":2,
         "operations":[{"type":"mask","id":"s","maskRef":"/p/mask_s.png"},$retouch]}
    """.trimIndent()

    private fun settings(
        vararg active: Pair<RetouchKind, Float> = arrayOf(RetouchKind.Blemish to 0.5f),
    ) = SkinRetouchSettings(
        strengths = RetouchKind.entries.associateWith { 0f } + active,
        engines = active.associate { (kind, _) -> kind to "engine-${kind.name}@1" },
    )

    private companion object {
        const val STRENGTHS = """"strengths":{"blemish":0.5,"shine":0,"darkCircles":0,"shavingShadow":0}"""
    }
}
