package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.StyleEffect
import com.diffuse.core.ai.StyleId
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.style.atIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * specs/vibe_edit.md §15: the plan-time replay must agree with the editor. Each case runs the same
 * steps through `StateSimulation` and through `EditDocument.withAdjust` — a style as its
 * `atIntensity` values, which is what `PlanRunner` commits — and compares the final settings.
 */
class PlanStateSimulationTest {

    @Test
    fun `a style's HSL overwrite and a later final step match the engine`() {
        val film = mapOf(AdjustKind.Temperature to 0.3f, AdjustKind.HslBlueSaturation to -0.4f, AdjustKind.Fade to 0.2f)
        val document = document()
            .withAdjust(AdjustKind.HslBlueSaturation, 0.4f)
            .withAdjust(AdjustKind.Exposure, 0.1f)
        val steps = listOf(
            PlanStep.Style(StyleId.FilmWarm, 50),
            PlanStep.Adjust(AdjustKind.Temperature, 0.25f),
            PlanStep.Adjust(AdjustKind.Exposure, 0.2f),
        )

        val replay = StateSimulation(context(document, listOf(StyleEffect(StyleId.FilmWarm, film)))).replay(steps)
        val engine = steps.fold(document) { doc, step ->
            when (step) {
                is PlanStep.Style -> film.atIntensity(step.intensity).entries
                    .fold(doc) { acc, (kind, value) -> acc.withAdjust(kind, value) }
                is PlanStep.Adjust -> doc.withAdjust(step.kind, step.value)
                else -> doc
            }
        }

        assertNull(replay.violation)
        assertEquals(settings(engine), replay.values)
        // The style replaced blue saturation 0.40 with −0.20; it did not add to it.
        assertEquals(-0.2f, replay.values[AdjustKind.HslBlueSaturation to null]!!, EPSILON)
    }

    @Test
    fun `blue saturation 0_40 made a little stronger is a larger final value in place`() {
        val document = document().withAdjust(AdjustKind.HslBlueSaturation, 0.4f)
        val steps = listOf(PlanStep.Adjust(AdjustKind.HslBlueSaturation, 0.5f))

        val replay = StateSimulation(context(document)).replay(steps)
        val engine = document.withAdjust(AdjustKind.HslBlueSaturation, 0.5f)

        assertEquals(settings(engine), replay.values)
        assertEquals(document.operations.single().id, engine.operations.single().id)
    }

    @Test
    fun `a target's setting is its own, apart from the whole photo's`() {
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.3f)
            .withMask(ImageRef("/p/mask_face.png"), "face")
            .withAdjust(AdjustKind.Exposure, 0.2f, "face")
        val steps = listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.3f, PlanTarget.Existing("t1", "face")))

        val replay = StateSimulation(context(document)).replay(steps)

        assertEquals(0.3f, replay.values[AdjustKind.Exposure to "t1"]!!, EPSILON)
        assertEquals(0.3f, replay.values[AdjustKind.Exposure to null]!!, EPSILON)
        val engine = document.withAdjust(AdjustKind.Exposure, 0.3f, "face")
        assertEquals(0.3f, engine.adjustValue(AdjustKind.Exposure, "face"))
    }

    private fun context(document: EditDocument, styles: List<StyleEffect> = emptyList()) = PlanContext(
        adjustments = document.operations.filterIsInstance<Operation.Adjust>().map {
            CurrentAdjust(it.kind, it.value, targetRef = it.maskId?.let { "t1" })
        },
        styles = styles,
    )

    private fun settings(document: EditDocument) = document.operations.filterIsInstance<Operation.Adjust>()
        .associate { (it.kind to it.maskId?.let { "t1" }) to it.value }

    private fun document() = EditDocument(id = "p", source = ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private companion object {
        const val EPSILON = 1e-6f
    }
}
