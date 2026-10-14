package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/vibe_edit.md §15, §18: the candidate's arithmetic and the snapshot, without a screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectCandidateMathTest {

    // ---- 강도 줄이기 ---------------------------------------------------------

    @Test
    fun `each setting moves halfway back to this input's value for the same mask`() {
        val base = document()
            .withAdjust(AdjustKind.Exposure, 0.4f)
            .withMask(ImageRef("/p/mask_face.png"), "face")
            .withAdjust(AdjustKind.Exposure, 0.2f, "face")
        val candidate = base
            .withAdjust(AdjustKind.Exposure, 0.6f)
            .withAdjust(AdjustKind.Exposure, 0.4f, "face")
            .withMask(ImageRef("/p/mask_new.png"), "new")
            .withAdjust(AdjustKind.Vibrance, 0.2f, "new")

        val reduced = reducedCandidate(base, candidate)

        assertEquals(0.5f, reduced.adjustValue(AdjustKind.Exposure), EPSILON)
        assertEquals(0.3f, reduced.adjustValue(AdjustKind.Exposure, "face"), EPSILON)
        // A new selection starts at 0.
        assertEquals(0.1f, reduced.adjustValue(AdjustKind.Vibrance, "new"), EPSILON)
        // Every op keeps its id and its place; masks are untouched.
        assertEquals(candidate.operations.map { it.id }, reduced.operations.map { it.id })
    }

    @Test
    fun `a style is halved per value it wrote, against what each value was before`() {
        val base = document().withAdjust(AdjustKind.Temperature, 0.1f).withAdjust(AdjustKind.HslBlueSaturation, 0.4f)
        // The style replaced temperature and blue saturation and added fade.
        val styled = base
            .withAdjust(AdjustKind.Temperature, 0.3f)
            .withAdjust(AdjustKind.HslBlueSaturation, -0.4f)
            .withAdjust(AdjustKind.Fade, 0.2f)

        val reduced = reducedCandidate(base, styled)

        assertEquals(0.2f, reduced.adjustValue(AdjustKind.Temperature), EPSILON)
        assertEquals(0f, reduced.adjustValue(AdjustKind.HslBlueSaturation), EPSILON)
        assertEquals(0.1f, reduced.adjustValue(AdjustKind.Fade), EPSILON)
    }

    @Test
    fun `a setting the candidate reset comes back halfway, in its old place`() {
        val base = document().withAdjust(AdjustKind.HslBlueHue, 0.2f).withAdjust(AdjustKind.Exposure, 0.1f)
        val hueId = base.operations.first().id
        val candidate = base.withAdjust(AdjustKind.HslBlueHue, 0f)

        val reduced = reducedCandidate(base, candidate)

        assertEquals(hueId, reduced.operations.first().id)
        assertEquals(0.1f, reduced.adjustValue(AdjustKind.HslBlueHue), EPSILON)
    }

    @Test
    fun `crops, cut-outs and generated patches are never halved`() {
        val base = document()
        val candidate = base.withMask(ImageRef("/p/mask_m.png"), "m").withCutOut("m")
            .withGenerativeErase("m", ImageRef("/p/erase_e.png"))
            .withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)

        assertEquals(candidate, reducedCandidate(base, candidate))
        assertFalse(hasNumericChange(base, candidate))
    }

    @Test
    fun `the files a candidate wrote are the ones its input does not reference`() {
        val base = document().withMask(ImageRef("/p/mask_old.png"), "old")
        val candidate = base.withMask(ImageRef("/p/mask_new.png"), "new")
            .withGenerativeFill("new", ImageRef("/p/fill_f.png"), "a bench")

        assertEquals(setOf("/p/mask_new.png", "/p/fill_f.png"), createdFiles(base, candidate))
    }

    // ---- the outside check -------------------------------------------------

    @Test
    fun `a change outside the allowed mask is measured, one inside is not`() {
        val before = solid(Color.GRAY)
        val insideOnly = solid(Color.GRAY).apply {
            for (x in 0 until HALF) for (y in 0 until SIZE) setPixel(x, y, Color.WHITE)
        }
        val left = mask { x, _ -> x < HALF }

        assertEquals(0f, outsideChange(before, insideOnly, left), EPSILON)
        assertEquals(1f, outsideChange(before, solid(Color.WHITE), left), EPSILON)
    }

    /**
     * §18: why the overlay exists. A selection that leaked over the whole frame makes every
     * pixel "inside", so a background change passes the pixel check — the check cannot say the
     * mask is the right thing.
     */
    @Test
    fun `a leaked mask passes the outside check, which is why the selection is confirmed`() {
        val leaked = mask { _, _ -> true }

        assertEquals(0f, outsideChange(solid(Color.GRAY), solid(Color.WHITE), leaked), EPSILON)
    }

    // ---- the snapshot ------------------------------------------------------

    @Test
    fun `the snapshot names every selection and value by an opaque ref, in render order`() {
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.4f)
            .withAdjust(AdjustKind.HslBlueSaturation, 0.4f)
            .withMask(ImageRef("/p/mask_face.png"), "face")
            .withAdjust(AdjustKind.Exposure, 0.2f, "face")
            .withMask(ImageRef("/p/mask_hand.png"), "hand")
            .withCrop(RectF(0f, 0.1f, 1f, 0.9f), 0f)

        val context = planContextOf(document, notes = mapOf("face" to "face"))

        assertEquals(listOf("t1", "t2"), context.targets.map { it.ref })
        assertEquals("face", context.targets[0].maskId)
        // §19: a plan's selection was found for its phrase, never confirmed by the user.
        assertEquals(TargetSource.Resolved to "face", context.targets[0].source to context.targets[0].description)
        // A selection this session did not make is unknown, never guessed.
        assertEquals(TargetSource.Unknown, context.targets[1].source)
        assertNull(context.targets[1].description)
        assertTrue("the last selection is the active one", context.targets[1].active)
        val faceExposure = context.adjustments.single { it.targetRef == "t1" }
        assertEquals(Triple("a3", 4, 0.2f), Triple(faceExposure.ref, faceExposure.order, faceExposure.value))
        assertEquals(
            listOf(
                "adjust exposure", "adjust blue saturation", "selection t1 (changes no pixels)",
                "adjust exposure", "selection t2 (changes no pixels)", "crop (applied last)",
            ),
            context.operations.map { it.kind },
        )
        assertEquals(0.1f, context.crop!!.top, EPSILON)
        // Mask ids and paths are local: the refs are what goes on the wire.
        assertFalse(context.operations.any { "mask_" in it.kind })
    }

    // ---- fixtures ---------------------------------------------------------

    private fun document() = EditDocument(id = "p", source = ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private fun solid(color: Int) =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun mask(set: (Int, Int) -> Boolean) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply {
        for (x in 0 until SIZE) {
            for (y in 0 until SIZE) setPixel(x, y, if (set(x, y)) Color.BLACK else Color.TRANSPARENT)
        }
    }

    private companion object {
        const val SIZE = 8
        const val HALF = 4
        const val EPSILON = 1e-6f
    }
}
