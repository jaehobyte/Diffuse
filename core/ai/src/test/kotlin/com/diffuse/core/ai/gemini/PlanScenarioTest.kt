package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * specs/vibe_edit.md §14's evaluation table as **structure**: for each photo/request pair, an
 * answer that keeps to the rules is accepted and the answer the table forbids is refused — where
 * the refusal is mechanical. A row marked [Scenario.forbiddenCheckable] = false forbids
 * something only a person looking at the photo can judge ("실루엣을 결함으로 취급"), so it is
 * listed here for the image evaluation and asserted on nothing but its accepted answer.
 *
 * None of this says whether Gemini actually answers well; that is the real-image evaluation
 * recorded in work/RESULT.md, run on [Scenario.fixture] three times per row.
 */
class PlanScenarioTest {

    data class Scenario(
        val fixture: String,
        val request: String,
        val accepted: String,
        val forbidden: String?,
        val forbiddenCheckable: Boolean = true,
    )

    @Test
    fun `every accepted answer passes and every mechanical violation is refused`() {
        SCENARIOS.forEach { scenario ->
            assertNull(scenario.toString(), violation(scenario.accepted, scenario.request))
            val forbidden = scenario.forbidden ?: return@forEach
            if (scenario.forbiddenCheckable) {
                assertNotNull("${scenario.fixture} / ${scenario.request}", violation(forbidden, scenario.request))
            } else {
                assertNull("documented as not machine-checkable", violation(forbidden, scenario.request))
            }
        }
    }

    @Test
    fun `face-only brightening is a face selection and a masked adjust`() {
        val steps = decode(FACE_ONLY).plan.steps
        assertEquals(PlanStep.Select("face"), steps[0])
        assertTrue(steps.drop(1).all { it is PlanStep.Adjust && it.masked })
    }

    @Test
    fun `brighter on raised exposure is a larger final setting`() {
        val step = decode(BRIGHTER_ON_RAISED).plan.steps.single() as PlanStep.Adjust
        assertEquals(AdjustKind.Exposure, step.kind)
        assertTrue("0.40 is already set; brighter must not lower it", step.value > RAISED_EXPOSURE)
    }

    @Test
    fun `a well-corrected photo may answer no_change with no tool at all`() {
        val proposal = decode(NO_CHANGE)
        assertEquals(PlanOutcome.NoChange, proposal.outcome)
        assertTrue(proposal.workflow.isEmpty())
    }

    @Test
    fun `the bus is selected rather than every green`() {
        val proposal = decode(BUS_ONLY)
        assertEquals(GoalScope.Subject, proposal.goals.single().scope)
        assertTrue(proposal.plan.steps.none { it is PlanStep.Adjust && it.kind.hsl != null })
    }

    private fun decode(args: String) = ProposalDecoder.decode(Json.parseToJsonElement(args).jsonObject)

    private fun violation(args: String, request: String): String? = try {
        ProposalCheck.violation(decode(args), request)
    } catch (e: Unplannable) {
        e.message
    }

    private companion object {
        const val RAISED_EXPOSURE = 0.4f

        fun goal(source: String, scope: String, extra: String = "") =
            """"goals":[{"id":"g1","text":"목표","source":"$source","scope":"$scope"$extra}]"""

        fun steps(vararg steps: String) =
            """"workflow":[${steps.joinToString(",") { it.replace("{G}", "\"goal_id\":\"g1\"") }}]"""

        fun ready(goal: String, workflow: String, extra: String = "") =
            """{"outcome":"ready",$goal,$workflow$extra}"""

        fun constraint(kind: String) = ""","intent":{"constraints":[{"text":"조건","kind":"$kind","explicit":true}]}"""

        fun notReady(outcome: String) = """{"outcome":"$outcome","reason":"이유"}"""

        const val SELECT_FACE = """{"tool":"select_region",{G},"args":{"phrase":"face"}}"""
        const val FACE_UP = """{"tool":"adjust",{G},"args":{"kind":"exposure","value":0.2}}"""
        const val GLOBAL_EXPOSURE = """{"tool":"adjust",{G},"args":{"kind":"exposure","value":0.3}}"""

        val FACE_ONLY = ready(goal("user", "subject"), steps(SELECT_FACE, FACE_UP), constraint("subject_only"))

        val BRIGHTER_ON_RAISED = ready(
            goal("user", "global"),
            steps("""{"tool":"adjust",{G},"args":{"kind":"exposure","value":0.55}}"""),
        )

        val NO_CHANGE = notReady("no_change")

        val BUS_ONLY = ready(
            goal("user", "subject"),
            steps(
                """{"tool":"select_region",{G},"args":{"phrase":"green bus"}}""",
                """{"tool":"adjust",{G},"args":{"kind":"contrast","value":0.15}}""",
            ),
            constraint("subject_only"),
        )

        /** Fixture names for the image evaluation; the photos themselves are not in the repo. */
        val SCENARIOS = listOf(
            Scenario(
                "backlit_portrait.jpg", "",
                accepted = ready(goal("default", "subject"), steps(SELECT_FACE, FACE_UP)),
                forbidden = ready(goal("user", "global"), steps(GLOBAL_EXPOSURE)),
            ),
            Scenario(
                "backlit_portrait.jpg", "얼굴만 밝게",
                accepted = FACE_ONLY,
                forbidden = ready(goal("user", "global"), steps(GLOBAL_EXPOSURE), constraint("subject_only")),
            ),
            Scenario(
                "silhouette.jpg", "실루엣은 유지해줘",
                accepted = notReady("no_change"),
                forbidden = ready(goal("user", "subject"), steps(SELECT_FACE, FACE_UP), constraint("other")),
                forbiddenCheckable = false,
            ),
            Scenario(
                "white_dress_black_suit.jpg", "둘 다 질감이 보이게",
                accepted = ready(
                    goal("user", "subject"),
                    steps(
                        """{"tool":"select_region",{G},"args":{"phrase":"white dress"}}""",
                        """{"tool":"adjust",{G},"args":{"kind":"highlights","value":-0.2}}""",
                        """{"tool":"select_region",{G},"args":{"phrase":"black suit"}}""",
                        """{"tool":"adjust",{G},"args":{"kind":"shadows","value":0.15}}""",
                    ),
                ),
                // Evening out the two materials' brightness is a pixel judgement.
                forbidden = ready(goal("user", "global"), steps(GLOBAL_EXPOSURE)),
                forbiddenCheckable = false,
            ),
            Scenario(
                "pastel_balloons_sky.jpg", "풍선의 연한 색을 살려줘",
                accepted = ready(
                    goal("user", "subject"),
                    steps(
                        """{"tool":"select_region",{G},"args":{"phrase":"balloons"}}""",
                        """{"tool":"adjust",{G},"args":{"kind":"vibrance","value":0.2}}""",
                    ),
                ),
                forbidden = ready(
                    goal("user", "subject"),
                    steps("""{"tool":"adjust",{G},"args":{"kind":"saturation","value":0.4,"masked":false}}"""),
                ),
            ),
            Scenario(
                "green_bus_plants.jpg", "버스만 강조해줘",
                accepted = BUS_ONLY,
                forbidden = ready(
                    goal("user", "color_range"),
                    steps("""{"tool":"adjust_color_range",{G},"args":{"color":"green","saturation":0.3}}"""),
                    constraint("subject_only"),
                ),
            ),
            Scenario(
                "wide_top_margin.jpg", "",
                accepted = notReady("no_change"),
                forbidden = ready(
                    goal("default", "global"),
                    steps("""{"tool":"crop_ratio",{G},"args":{"ratio":"square"}}"""),
                ),
            ),
            Scenario(
                "wide_top_margin.jpg", "위 여백 줄여줘",
                accepted = notReady("unsupported"),
                // No coordinates exist on the wire at all: a rect is not expressible.
                forbidden = ready(
                    goal("user", "global"),
                    steps("""{"tool":"crop_ratio",{G},"args":{"ratio":"free"}}"""),
                ),
            ),
            Scenario(
                "night_sunset.jpg", "",
                accepted = notReady("no_change"),
                // "무조건 밝게 / 중성 화이트밸런스" is a judgement about this photo, not the plan's shape.
                forbidden = ready(
                    goal("default", "global"),
                    steps("""{"tool":"adjust",{G},"args":{"kind":"temperature","value":-0.4}}""", GLOBAL_EXPOSURE),
                ),
                forbiddenCheckable = false,
            ),
            Scenario(
                "dark_texture.jpg", "디테일을 살려줘",
                accepted = ready(
                    goal("user", "global"),
                    """"workflow":[{"tool":"adjust","goal_id":"g1","args":{"kind":"shadows","value":0.15},""" +
                        """"caution":"노이즈가 늘 수 있어요"}]""",
                ),
                forbidden = null,
            ),
            Scenario(
                "flowers.jpg", "봄처럼 화사하게, 배경은 그대로",
                accepted = ready(
                    goal("user", "subject"),
                    steps(
                        """{"tool":"select_region",{G},"args":{"phrase":"flowers"}}""",
                        """{"tool":"adjust",{G},"args":{"kind":"exposure","value":0.15}}""",
                        """{"tool":"adjust",{G},"args":{"kind":"vibrance","value":0.2}}""",
                    ),
                    constraint("keep_background"),
                ),
                forbidden = ready(
                    goal("user", "global"),
                    steps("""{"tool":"apply_style",{G},"args":{"style":"pastel-soft"}}"""),
                    constraint("keep_background"),
                ),
            ),
            Scenario(
                "well_corrected.jpg", "",
                accepted = NO_CHANGE,
                forbidden = ready(goal("user", "global"), steps(GLOBAL_EXPOSURE)),
            ),
            Scenario(
                "group_people.jpg", "그 사람만 밝게",
                accepted = notReady("needs_clarification"),
                // Picking one person is a phrase choice the app cannot tell from a correct one.
                forbidden = ready(
                    goal("user", "subject"),
                    steps("""{"tool":"select_region",{G},"args":{"phrase":"person on the left"}}""", FACE_UP),
                ),
                forbiddenCheckable = false,
            ),
            Scenario(
                "exposure_raised.jpg", "더 밝게",
                accepted = BRIGHTER_ON_RAISED,
                forbidden = null,
            ),
        )
    }
}
