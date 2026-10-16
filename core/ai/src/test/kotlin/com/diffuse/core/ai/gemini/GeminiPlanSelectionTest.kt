package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.PlanTargetState
import com.diffuse.core.ai.TopazBlock
import com.diffuse.core.ai.TopazCapability
import com.diffuse.core.ai.items
import com.diffuse.core.ai.selected
import com.diffuse.core.ai.selectionProblem
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ai.topazBlock
import com.diffuse.core.ai.withValues
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * specs/vibe_edit.md §19: a decoded proposal as toggleable items. A selection is never an item;
 * it runs exactly when one of the items consuming it is chosen. Ids come from the wire position
 * and survive the crop/Topaz reorder, and every choice passes the rules the whole proposal did.
 */
class GeminiPlanSelectionTest {

    /** Two adjusts share the face selection; a different subject has its own; one reuses t1. */
    private val portrait = decode(
        """{"outcome":"ready","direction":{"subject":"인물","objective":"얼굴","success":"비교","source":"default"},
          "goals":[{"id":"g1","text":"얼굴","source":"default","scope":"subject","target":"얼굴"},
                   {"id":"g2","text":"표지판","source":"default","scope":"subject","target":"표지판"},
                   {"id":"g3","text":"전체","source":"default","scope":"global"}],
          "workflow":[
            {"tool":"select_region","goal_id":"g1","args":{"phrase":"face","target":"n1"}},
            {"tool":"adjust","goal_id":"g1","args":{"kind":"exposure","value":0.2,"target":"n1"}},
            {"tool":"adjust","goal_id":"g1","args":{"kind":"contrast","value":0.1,"target":"n1"}},
            {"tool":"adjust","goal_id":"g1","args":{"kind":"shadows","value":0.1,"target":"t1"}},
            {"tool":"crop_ratio","goal_id":"g3","args":{"ratio":"portrait_4_5"}},
            {"tool":"select_region","goal_id":"g2","args":{"phrase":"sign","target":"n2"}},
            {"tool":"erase_selection","goal_id":"g2","args":{"target":"n2"}},
            {"tool":"topaz_enhance","goal_id":"g3","args":{"purpose":"denoise"}},
            {"tool":"adjust","goal_id":"g3","args":{"kind":"temperature","value":0.05}}
          ]}""",
    )

    /** D109: a slider's value lands on its own adjustment item and nowhere else. */
    @Test
    fun `the user's values replace only the adjustment items they name, within the kind's range`() {
        val tuned = portrait.withValues(mapOf("s2" to 0.5f, "s7" to 0.9f, "s9" to 3f))
        val steps = tuned.workflow.associate { it.id to it.step }

        assertEquals(0.5f, (steps["s2"] as PlanStep.Adjust).value)
        assertEquals("the proposal's own value stays", 0.1f, (steps["s3"] as PlanStep.Adjust).value)
        assertEquals("an erase is not an adjustment", PlanStep.Erase("n2"), steps["s7"])
        assertEquals(AdjustKind.Temperature.range.endInclusive, (steps["s9"] as PlanStep.Adjust).value)
        assertEquals(portrait.items.map { it.id }, tuned.items.map { it.id })
        assertEquals("the proposal itself is untouched", 0.2f, (portrait.workflow[1].step as PlanStep.Adjust).value)
        assertEquals(portrait, portrait.withValues(emptyMap()))
    }

    @Test
    fun `every step but a selection is its own item, with the selections it needs`() {
        val items = portrait.items

        assertEquals(listOf("s2", "s3", "s4", "s7", "s9", "s5", "s8"), items.map { it.id })
        assertEquals(listOf("s1"), items.first { it.id == "s2" }.needs.map { it.id })
        assertEquals(listOf("s1"), items.first { it.id == "s3" }.needs.map { it.id })
        assertTrue("an existing target needs no new selection", items.first { it.id == "s4" }.needs.isEmpty())
        assertEquals(listOf("s6"), items.first { it.id == "s7" }.needs.map { it.id })
    }

    @Test
    fun `one of two shared-selection items off keeps the selection, both off drop it`() {
        val all = portrait.items.map { it.id }.toSet()

        val noContrast = portrait.selected(all - "s3").plan.steps
        assertTrue(PlanStep.Select("face", "n1") in noContrast)
        assertTrue(noContrast.none { it is PlanStep.Adjust && it.kind == AdjustKind.Contrast })

        val noFace = portrait.selected(all - "s2" - "s3").plan.steps
        assertTrue("no consumer, no segmentation", noFace.none { it == PlanStep.Select("face", "n1") })
        assertTrue("the other subject keeps its own", PlanStep.Select("sign", "n2") in noFace)
        assertTrue(
            "an existing target is reused, not reselected",
            noFace.any { it is PlanStep.Adjust && it.target is PlanTarget.Existing },
        )
    }

    @Test
    fun `a choice keeps the original order and ids, crop last of the local steps and topaz after it`() {
        val all = portrait.items.map { it.id }.toSet()
        val chosen = portrait.selected(all - "s9")

        assertEquals(listOf("s1", "s2", "s3", "s4", "s6", "s7", "s5", "s8"), chosen.workflow.map { it.id })
        assertEquals(chosen.items.map { it.id }, portrait.items.map { it.id } - "s9")
    }

    @Test
    fun `every subset of a valid proposal passes the checks again`() {
        val context = PlanContext(targets = listOf(PlanTargetState("t1", "mask-1", active = false)))
        val ids = portrait.items.map { it.id }
        assertNull(portrait.selectionProblem(context))
        // Every subset of seven items: 128 choices, each run through the same rules.
        (0 until (1 shl ids.size)).forEach { bits ->
            val chosen = ids.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
            assertNull(chosen.toString(), portrait.selected(chosen).selectionProblem(context))
        }
    }

    @Test
    fun `a missing key or an input the purpose cannot take blocks the topaz item`() {
        val step = portrait.items.first { it.step is PlanStep.Topaz }.step as PlanStep.Topaz

        assertEquals(TopazBlock.NoKey, topazBlock(step, TopazCapability(configured = false, 100, 100)))
        assertEquals(
            TopazBlock.Unavailable(TopazFailure.InputHasAlpha),
            topazBlock(step, TopazCapability(configured = true, 100, 100, hasAlpha = true)),
        )
        assertNull(topazBlock(step, TopazCapability(configured = true, 100, 100)))
        assertEquals(TopazPurpose.Denoise, step.purpose)
    }

    /** §16, §19: each purpose described on its own, and the two pairs people confuse told apart. */
    @Test
    fun `the catalog describes every topaz purpose and the local tools it is not`() {
        TopazPurpose.entries.forEach { purpose ->
            assertTrue(purpose.wireName, PLAN_SYSTEM_INSTRUCTION.contains("- ${purpose.wireName}: "))
        }
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("only raises edge contrast and never brings back blurred detail"))
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("more pixels in the same frame"))
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("never describe it as doing several purposes at once"))
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("a separate default goal even if the user did not ask for removal"))
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("Never remove the main subject"))
    }

    private fun decode(args: String): PlanProposal {
        val context = PlanContext(targets = listOf(PlanTargetState("t1", "mask-1", active = false)))
        val proposal = ProposalDecoder.decode(Json.parseToJsonElement(args).jsonObject, context)
        assertNull(ProposalCheck.violation(proposal, "", context))
        // The client's reorder, so ids are seen surviving it.
        val (moved, kept) = proposal.workflow.partition { it.step is PlanStep.Crop || it.step is PlanStep.Topaz }
        return proposal.copy(workflow = kept + moved.sortedBy { it.step is PlanStep.Topaz })
    }
}
