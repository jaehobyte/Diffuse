package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.PlanTargetState
import com.diffuse.core.ai.StyleEffect
import com.diffuse.core.ai.StyleId
import com.diffuse.core.ai.TopazCapability
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
    fun `face-only brightening is a face selection and an adjust on it`() {
        val steps = decode(FACE_ONLY).plan.steps
        assertEquals(PlanStep.Select("face", "n1"), steps[0])
        assertTrue(steps.drop(1).all { it is PlanStep.Adjust && it.target == PlanTarget.New("n1") })
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

    /**
     * review N2: g1 selects the face, g2 selects the hair, g1 adjusts again. The old check saw g1's
     * earlier select and let the step run on the hair mask; now the step names its target, and
     * a target that is not the current selection is reached by name, never by fallback.
     */
    @Test
    fun `g1, g2, g1 - the third step acts on g1's own selection or is refused`() {
        val explicit = ready(
            goals("subject", "subject"),
            steps(
                select("face", "n1"), adjust("exposure", 0.2, "n1"),
                select("hair", "n2", goal = "g2"), adjust("contrast", 0.1, "n2", goal = "g2"),
                adjust("shadows", 0.1, "n1"),
            ),
        )
        val implicit = ready(
            goals("subject", "subject"),
            steps(
                select("face", "n1"), adjust("exposure", 0.2, "n1"),
                select("hair", "n2", goal = "g2"), adjust("contrast", 0.1, "n2", goal = "g2"),
                """{"tool":"adjust","goal_id":"g1","args":{"kind":"shadows","value":0.1}}""",
            ),
        )
        val erasesOld = ready(
            goals("subject", "subject"),
            steps(
                select("face", "n1"), adjust("exposure", 0.2, "n1"),
                select("hair", "n2", goal = "g2"), adjust("contrast", 0.1, "n2", goal = "g2"),
                """{"tool":"erase_selection","goal_id":"g1","args":{"target":"n1"}}""",
            ),
        )

        assertNull(violation(explicit, "얼굴 밝게, 머리 대비"))
        assertEquals(PlanTarget.New("n1"), (decode(explicit).plan.steps[4] as PlanStep.Adjust).target)
        assertEquals("Adjust outside its Subject goal", violation(implicit, "얼굴 밝게, 머리 대비"))
        assertEquals("Erase on a selection that is not the current one", violation(erasesOld, "얼굴 밝게, 머리 대비, 얼굴 지워"))
    }

    /** §17: keep_background keeps the outside's pixels, not the frame — a requested crop runs. */
    @Test
    fun `keep background allows a requested crop and refuses a cut-out`() {
        val request = "얼굴만 밝게, 배경 그대로, 스토리 비율"
        val crop = ready(
            goals("subject", "global"),
            steps(
                select("face", "n1"),
                adjust("exposure", 0.2, "n1"),
                """{"tool":"crop_ratio","goal_id":"g2","args":{"ratio":"story_9_16"}}""",
            ),
            constraint("keep_background"),
        )
        val cutOut = ready(
            goals("subject"),
            steps(select("person", "n1"), """{"tool":"cut_out_selection","goal_id":"g1","args":{"target":"n1"}}"""),
            constraint("keep_background"),
        )
        val frame = ready(
            goals("global"),
            steps("""{"tool":"crop_ratio","goal_id":"g1","args":{"ratio":"story_9_16"}}"""),
            constraint("keep_frame"),
        )

        assertNull(violation(crop, request))
        assertEquals("CutOut under KeepBackground", violation(cutOut, "배경 그대로 사람만"))
        assertEquals("Crop under KeepFrame", violation(frame, "구도 전부 유지하고 스토리로"))
    }

    /** §17: three strengths of "keep the colours", and the brightness a colour rule still allows. */
    @Test
    fun `colour constraints refuse by strength`() {
        fun plan(kind: String, constraintKind: String) =
            ready(goals("global"), steps(adjust(kind, 0.1)), constraint(constraintKind))

        assertNull(violation(plan("saturation", "keep_hue"), "색감은 유지"))
        assertEquals("Adjust under KeepHue", violation(plan("temperature", "keep_hue"), "색감은 유지"))
        assertEquals("Adjust under KeepColors", violation(plan("vibrance", "keep_colors"), "색은 바꾸지 마"))
        assertNull(violation(plan("exposure", "keep_colors"), "색은 바꾸지 마"))
        assertEquals("Adjust under KeepExactColors", violation(plan("exposure", "keep_exact_colors"), "RGB 그대로"))
    }

    /**
     * §16, §19: Topaz is one whole-photo hand-off that may follow local steps and may be a default
     * suggestion; never twice, never after a cut-out, never under an **explicit** preservation
     * constraint, and only with options its purpose offers.
     */
    @Test
    fun `topaz is one hand-off beside local steps, with options its purpose offers`() {
        fun topaz(args: String, source: String = "user") =
            """{"tool":"topaz_enhance","goal_id":"g1","args":$args}"""
                .let { ready(goals("global", source = source), steps(it), source = source) }

        assertNull(violation(topaz("""{"purpose":"sharpen","scale":1,"sharpen_strength":0.4}"""), "흐린 걸 복원"))
        assertNull(violation(topaz("""{"purpose":"denoise"}""", source = "default"), ""))
        assertNull(violation(topaz("""{"purpose":"upscale","scale":2}""", source = "default"), ""))
        assertNull(violation(topaz("""{"purpose":"colorize"}""", source = "default"), ""))
        assertNull(violation(topaz("""{"purpose":"restore","face_recovery":true}""", source = "default"), ""))
        assertEquals("topaz OptionUnsupported", violation(topaz("""{"purpose":"lighting","scale":2}"""), "조명"))
        assertEquals(
            "topaz OptionUnsupported",
            violation(topaz("""{"purpose":"denoise","sharpen_strength":0.5}"""), "노이즈"),
        )
        assertEquals(
            "topaz OptionUnsupported",
            violation(topaz("""{"purpose":"lighting","face_recovery":true}"""), "얼굴 복원"),
        )
        val mixed = ready(
            goals("global"),
            steps("""{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"sharpen"}}""", adjust("exposure", 0.1)),
        )
        assertNull(violation(mixed, "선명하고 밝게"))
        val twice = ready(
            goals("global"),
            steps(
                """{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"sharpen"}}""",
                """{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"denoise"}}""",
            ),
        )
        assertEquals("more than one topaz", violation(twice, "선명하고 깨끗하게"))
        val cutOut = ready(
            goals("subject", "global"),
            steps(
                select("person", "n1"),
                """{"tool":"cut_out_selection","goal_id":"g1","args":{"target":"n1"}}""",
                """{"tool":"topaz_enhance","goal_id":"g2","args":{"purpose":"upscale","scale":2}}""",
            ),
        )
        assertEquals("topaz after a cut-out", violation(cutOut, "사람만 남기고 크게"))
        val constrained = ready(
            goals("global"),
            steps("""{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"lighting"}}"""),
            constraint("keep_background"),
        )
        assertEquals("topaz under a preservation constraint", violation(constrained, "배경 그대로 조명"))
        val inferred = ready(
            goals("global", source = "default"),
            steps("""{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"lighting"}}"""),
            constraint("keep_background", explicit = false),
            source = "default",
        )
        assertNull("an inferred constraint is the model's taste, not a rule", violation(inferred, ""))
    }

    /** §16: an input the purpose cannot take — alpha, too many pixels — is refused at plan time. */
    @Test
    fun `topaz is checked against the snapshot's input`() {
        val plan = ready(
            goals("global"),
            steps("""{"tool":"topaz_enhance","goal_id":"g1","args":{"purpose":"upscale","scale":4}}"""),
        )
        val alpha = PlanContext(
            topaz = TopazCapability(configured = true, inputWidth = 100, inputHeight = 100, hasAlpha = true),
        )
        val huge = PlanContext(topaz = TopazCapability(configured = true, inputWidth = 30_000, inputHeight = 20_000))

        assertEquals("topaz InputHasAlpha", violation(plan, "해상도 높여줘", alpha))
        assertEquals("topaz LimitExceeded", violation(plan, "해상도 높여줘", huge))
    }

    /**
     * §15: the plan replays on the snapshot with the editor's rules. A style's actual values decide
     * overlap: an adjust on a kind it does not write is independent; one that repeats or reverses
     * it, or that it later replaces, is refused.
     */
    @Test
    fun `style overlap is judged on the catalog's values, not the style's name`() {
        val context = PlanContext(
            styles = listOf(
                StyleEffect(
                    StyleId.FilmWarm,
                    mapOf(AdjustKind.Temperature to 0.3f, AdjustKind.HslBlueSaturation to -0.4f),
                ),
            ),
        )
        fun plan(vararg extra: String, styleFirst: Boolean = true): String {
            val style = """{"tool":"apply_style","goal_id":"g1","args":{"style":"film-warm","intensity":50}}"""
            val list = if (styleFirst) listOf(style) + extra else extra.toList() + style
            return ready(goals("global"), steps(*list.toTypedArray()))
        }

        assertNull(violation(plan(adjust("exposure", 0.1)), "필름 느낌, 조금 밝게", context))
        // At 50 the style writes temperature 0.15: setting it again to 0.15 repeats it…
        assertEquals("an adjust that repeats the style", violation(plan(adjust("temperature", 0.15)), "필름", context))
        // …going back down undoes it, going further is a deliberate final step.
        assertEquals("an adjust that reverses the style", violation(plan(adjust("temperature", 0.05)), "필름", context))
        assertNull(violation(plan(adjust("temperature", 0.25)), "필름, 더 따뜻하게", context))
        // An HSL value the style writes behaves the same; one set before it is thrown away.
        val blueBefore = plan(
            """{"tool":"adjust_color_range","goal_id":"g1","args":{"color":"blue","saturation":0.4}}""",
            styleFirst = false,
        )
        assertEquals("an adjust the style then replaces", violation(blueBefore, "파랑 진하게, 필름", context))
        // Without the catalog's values nothing is claimed about the style at all.
        assertNull(violation(plan(adjust("temperature", 0.15)), "필름"))
    }

    @Test
    fun `a style that writes nothing at its intensity is refused`() {
        val context = PlanContext(styles = listOf(StyleEffect(StyleId.FilmWarm, mapOf(AdjustKind.Temperature to 0.3f))))
        val zero = ready(
            goals("global"),
            steps("""{"tool":"apply_style","goal_id":"g1","args":{"style":"film-warm","intensity":0}}"""),
        )

        assertEquals("a style that changes nothing", violation(zero, "필름", context))
    }

    @Test
    fun `the same setting twice in one plan is refused`() {
        val twice = ready(goals("global"), steps(adjust("exposure", 0.1), adjust("exposure", 0.2)))

        assertEquals("the same setting set twice", violation(twice, "밝게"))
    }

    /** §15: a ready plan carries its one objective, marked default when it was inferred. */
    @Test
    fun `ready needs a direction, and a user direction needs a request`() {
        val none = """{"outcome":"ready",${goals("global")},${steps(adjust("exposure", 0.1))}}"""

        assertEquals("ready without a direction", violation(none, "밝게"))
        assertEquals(
            "a user direction without a request",
            violation(ready(goals("global", source = "default"), steps(adjust("exposure", 0.1)), source = "user"), ""),
        )
        val inferred = ready(goals("global", source = "default"), steps(adjust("exposure", 0.1)), source = "default")
        assertEquals(GoalSource.Default, decode(inferred).direction?.source)
    }

    /** §15: a new label may not shadow a snapshot ref; a selection must be used by name. */
    @Test
    fun `labels are declared once and never shadow the snapshot`() {
        val context = PlanContext(targets = listOf(PlanTargetState("t1", "m1", active = true)))
        val shadow = ready(goals("subject"), steps(select("face", "t1"), adjust("exposure", 0.2, "t1")))
        val unused = ready(
            goals("subject", "global"),
            steps(select("face", "n1"), adjust("exposure", 0.2, goal = "g2")),
        )

        assertEquals("a new selection reuses a snapshot ref", violation(shadow, "얼굴", context))
        assertEquals("a selection nothing uses", violation(unused, "얼굴"))
    }

    private fun decode(args: String, context: PlanContext = PlanContext()) =
        ProposalDecoder.decode(Json.parseToJsonElement(args).jsonObject, context)

    private fun violation(args: String, request: String, context: PlanContext = PlanContext()): String? = try {
        ProposalCheck.violation(decode(args, context), request, context)
    } catch (e: Unplannable) {
        e.message
    }

    private companion object {
        const val RAISED_EXPOSURE = 0.4f

        fun goal(source: String, scope: String, extra: String = "") =
            """"goals":[{"id":"g1","text":"목표","source":"$source","scope":"$scope"$extra}]"""

        fun goals(vararg scopes: String, source: String = "user") = """"goals":[""" +
            scopes.mapIndexed { i, scope -> """{"id":"g${i + 1}","text":"목표","source":"$source","scope":"$scope"}""" }
                .joinToString(",") + "]"

        fun steps(vararg steps: String) =
            """"workflow":[${steps.joinToString(",") { it.replace("{G}", "\"goal_id\":\"g1\"") }}]"""

        fun select(phrase: String, label: String, goal: String = "g1") =
            """{"tool":"select_region","goal_id":"$goal","args":{"phrase":"$phrase","target":"$label"}}"""

        fun adjust(kind: String, value: Double, target: String? = null, goal: String = "g1") =
            """{"tool":"adjust","goal_id":"$goal","args":{"kind":"$kind","value":$value""" +
                (target?.let { ""","target":"$it"""" } ?: "") + "}}"

        fun direction(source: String) =
            """"direction":{"subject":"주제","objective":"목표","success":"비교 기준","source":"$source"}"""

        /** [source] is the direction's; a goal-level default makes it default too, unless told. */
        fun ready(
            goal: String,
            workflow: String,
            extra: String = "",
            source: String = if (goal.contains("\"default\"")) "default" else "user",
        ) = """{"outcome":"ready",${direction(source)},$goal,$workflow$extra}"""

        fun constraint(kind: String, explicit: Boolean = true) =
            ""","intent":{"constraints":[{"text":"조건","kind":"$kind","explicit":$explicit}]}"""

        fun notReady(outcome: String) = """{"outcome":"$outcome","reason":"이유"}"""

        val SELECT_FACE = select("face", "n1")
        val FACE_UP = adjust("exposure", 0.2, "n1")
        val GLOBAL_EXPOSURE = adjust("exposure", 0.3)

        val FACE_ONLY = ready(goal("user", "subject"), steps(SELECT_FACE, FACE_UP), constraint("subject_only"))

        val BRIGHTER_ON_RAISED = ready(goal("user", "global"), steps(adjust("exposure", 0.55)))

        val NO_CHANGE = notReady("no_change")

        val BUS_ONLY = ready(
            goal("user", "subject"),
            steps(select("green bus", "n1"), adjust("contrast", 0.1, "n1")),
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
                        select("white dress", "n1"), adjust("highlights", -0.2, "n1"),
                        select("black suit", "n2"), adjust("shadows", 0.15, "n2"),
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
                        """{"tool":"select_region",{G},"args":{"phrase":"balloons","target":"n1","multiple":true}}""",
                        adjust("vibrance", 0.2, "n1"),
                    ),
                ),
                forbidden = ready(goal("user", "subject"), steps(adjust("saturation", 0.4))),
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
                // §19: a default reframe is allowed; whether square helps this photo is a judgement.
                forbidden = ready(
                    goal("default", "global"),
                    steps("""{"tool":"crop_ratio",{G},"args":{"ratio":"square"}}"""),
                ),
                forbiddenCheckable = false,
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
                    steps(adjust("temperature", -0.4), GLOBAL_EXPOSURE),
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
                        """{"tool":"select_region",{G},"args":{"phrase":"flowers","target":"n1","multiple":true}}""",
                        adjust("exposure", 0.15, "n1"),
                        adjust("vibrance", 0.2, "n1"),
                    ),
                    constraint("keep_background"),
                ),
                forbidden = ready(
                    goal("user", "global"),
                    steps("""{"tool":"apply_style",{G},"args":{"style":"pastel-soft","intensity":50}}"""),
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
                    steps(select("person on the left", "n1"), FACE_UP),
                ),
                forbiddenCheckable = false,
            ),
            Scenario(
                "exposure_raised.jpg", "더 밝게",
                accepted = BRIGHTER_ON_RAISED,
                forbidden = null,
            ),
            Scenario(
                "blurry_small.jpg", "흐릿한 거 선명하게 복원해줘",
                accepted = ready(
                    goal("user", "global"),
                    steps("""{"tool":"topaz_enhance",{G},"args":{"purpose":"sharpen","scale":1}}"""),
                ),
                // §19: two purposes are two runs; the plan hands off one.
                forbidden = ready(
                    goal("user", "global"),
                    steps(
                        """{"tool":"topaz_enhance",{G},"args":{"purpose":"sharpen"}}""",
                        """{"tool":"topaz_enhance",{G},"args":{"purpose":"upscale","scale":2}}""",
                    ),
                ),
            ),
            Scenario(
                "bw_old_photo.jpg", "흑백은 그대로 두고 또렷하게",
                accepted = ready(
                    goal("user", "global"),
                    steps(adjust("contrast", 0.1)),
                    constraint("keep_colors"),
                ),
                // The user's own words keep the colours: colourising is refused, not just unlikely.
                forbidden = ready(
                    goal("default", "global"),
                    steps("""{"tool":"topaz_enhance",{G},"args":{"purpose":"colorize"}}"""),
                    constraint("keep_colors"),
                ),
            ),
            Scenario(
                "lake_with_bin.jpg", "",
                // §19: a visible distraction may be removed unasked, as its own default goal.
                accepted = ready(
                    goals("subject", "global", source = "default"),
                    steps(
                        select("trash can", "n1"),
                        """{"tool":"erase_selection",{G},"args":{"target":"n1"}}""",
                        adjust("contrast", 0.08, goal = "g2"),
                    ),
                    source = "default",
                ),
                // An erase still consumes a selection made right before it.
                forbidden = ready(
                    goals("subject", source = "default"),
                    steps("""{"tool":"erase_selection",{G},"args":{"target":"n1"}}"""),
                    source = "default",
                ),
            ),
            Scenario(
                "lake_with_bin.jpg", "배경은 바꾸지 마",
                accepted = notReady("no_change"),
                // The user's explicit word wins over a default suggestion.
                forbidden = ready(
                    goal("default", "global"),
                    steps("""{"tool":"apply_style",{G},"args":{"style":"crisp-landscape","intensity":50}}"""),
                    constraint("keep_background"),
                ),
            ),
        )
    }
}
