package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.ColorNote
import com.diffuse.core.ai.ColorRole
import com.diffuse.core.ai.Confidence
import com.diffuse.core.ai.Constraint
import com.diffuse.core.ai.ConstraintKind
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.GoalScope
import com.diffuse.core.ai.GoalSource
import com.diffuse.core.ai.IntentItem
import com.diffuse.core.ai.Observation
import com.diffuse.core.ai.OperationNote
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanDirection
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.PlanTargetState
import com.diffuse.core.ai.StyleEffect
import com.diffuse.core.ai.StyleId
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.ai.TopazCapability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.HslBand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/vibe_edit.md §5, §6, §12, §14. MockWebServer binds localhost only. These tests check the
 * request, the envelope's decoding and the all-or-nothing refusal — **structure**, not whether a
 * real model understands a photo, which only the image evaluation can say.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiPlanClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiPlanClient
    private var config = GeminiConfig(API_KEY)

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = GeminiConfig(API_KEY, server.url("/").toString().trimEnd('/'))
        client = GeminiPlanClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the request -----------------------------------------------------

    @Test
    fun `it posts to the text model, not the image one`() = runTest {
        server.enqueue(calls(READY))

        client.plan(JPEG, DIRECT)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", recorded.requestUrl?.encodedPath)
    }

    @Test
    fun `the key travels in the header and never in the URL`() = runTest {
        server.enqueue(calls(READY))

        client.plan(JPEG, DIRECT)

        val recorded = server.takeRequest()
        assertEquals(API_KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.requestUrl?.queryParameter("key"))
        assertFalse(recorded.requestUrl.toString().contains(API_KEY))
    }

    @Test
    fun `a selection an earlier plan found is described as found, never as confirmed by the user`() = runTest {
        server.enqueue(calls(READY))
        val context = PlanContext(
            hasSelection = true,
            targets = listOf(
                PlanTargetState("t1", "mask-found-id", active = true, "sky", TargetSource.Resolved, order = 1),
            ),
            operations = listOf(OperationNote(1, "selection", "t1")),
        )

        client.plan(JPEG, PlanRequest(REQUEST, PlanMode.Direct, context))

        val settings = parts(sentBody())[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(settings, settings.contains("- t1 (active): \"sky\", found for that phrase by an earlier plan"))
        assertTrue(settings, settings.contains("not confirmed by the user"))
        assertFalse(settings, settings.contains("confirmed by the user in this session"))
        assertFalse(settings, settings.contains("mask-found-id"))
    }

    @Test
    fun `the body carries the instruction, the image, the mode, the settings and the text verbatim`() = runTest {
        server.enqueue(calls(READY))
        val context = PlanContext(
            adjustments = listOf(
                CurrentAdjust(AdjustKind.Exposure, 0.4f, ref = "a1", order = 1),
                CurrentAdjust(AdjustKind.HslBlueSaturation, 0.4f, ref = "a2", order = 2),
                CurrentAdjust(AdjustKind.Exposure, 0.2f, targetRef = "t1", ref = "a3", order = 4),
            ),
            hasSelection = true,
            targets = listOf(
                PlanTargetState("t1", "mask-secret-id", active = true, "얼굴", TargetSource.Confirmed, order = 3),
                PlanTargetState("t2", "mask-legacy-id", active = false, order = 5),
            ),
            operations = listOf(
                OperationNote(1, "adjust exposure"), OperationNote(2, "adjust blue saturation"),
                OperationNote(3, "selection", "t1"), OperationNote(4, "adjust exposure", "t1"),
                OperationNote(5, "selection", "t2"),
            ),
            imageWidth = 1080,
            imageHeight = 720,
            styles = listOf(StyleEffect(StyleId.FilmWarm, mapOf(AdjustKind.Temperature to 0.2f))),
            topaz = TopazCapability(configured = false, inputWidth = 4096, inputHeight = 2731),
        )

        client.plan(JPEG, PlanRequest(REQUEST, PlanMode.Direct, context))

        val body = sentBody()
        assertEquals(PLAN_SYSTEM_INSTRUCTION, systemText(body))
        val parts = parts(body)
        assertEquals("image/jpeg", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        val settings = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(settings, settings.contains("Mode: direct"))
        assertTrue(settings, settings.contains("already rendered with every operation"))
        assertTrue(settings, settings.contains("Preview: 1080x720"))
        assertTrue(settings, settings.contains("- exposure=0.40 (a1, #1)"))
        assertTrue(settings, settings.contains("every band and channel not listed is 0"))
        assertTrue(settings, settings.contains("- blue saturation=0.40 (a2, #2)"))
        // §15: the face is t1 with its own setting; the legacy one is unknown, never guessed.
        assertTrue(settings, settings.contains("- t1 (active): \"얼굴\", confirmed by the user"))
        assertTrue(settings, settings.contains("settings: exposure=0.20 (a3, #4)"))
        assertTrue(settings, settings.contains("- t2: unknown"))
        assertTrue(settings, settings.contains("3. selection on t1"))
        assertTrue(settings, settings.contains("- film-warm: temperature=0.20"))
        assertTrue(settings, settings.contains("no key - the user must add one"))
        assertTrue(settings, settings.contains("input 4096x2731"))
        assertTrue(settings, settings.contains("- upscale: scale 2/4, face_recovery available"))
        // Local ids stay local.
        assertFalse(settings, settings.contains("mask-secret-id"))
        assertFalse(settings, settings.contains("mask-legacy-id"))
        assertEquals(REQUEST, parts[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a recommendation with no text sends the photo and its context only`() = runTest {
        server.enqueue(calls(READY_DEFAULT))

        client.plan(JPEG, PlanRequest("", PlanMode.Recommend))

        val parts = parts(sentBody())
        assertEquals(2, parts.size)
        val context = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(context.contains("Mode: recommend"))
        assertTrue(context.contains("Request: (none)"))
        assertTrue(context, context.contains("a kind not listed is 0; an adjust replaces the value in place): none"))
        assertTrue(context, context.contains("Selections (targets): none"))
    }

    @Test
    fun `a recommendation with text sends that text verbatim too`() = runTest {
        server.enqueue(calls(READY))

        client.plan(JPEG, PlanRequest("버스만 강조해줘", PlanMode.Recommend))

        val parts = parts(sentBody())
        assertTrue(parts[1].jsonObject["text"]!!.jsonPrimitive.content.contains("Mode: recommend"))
        assertEquals("버스만 강조해줘", parts[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    /** §14: the two modes are one evaluation policy — the same instruction and the same envelope. */
    @Test
    fun `both modes send the same instruction, the same priorities and the same one function`() = runTest {
        server.enqueue(calls(READY_DEFAULT))
        server.enqueue(calls(READY))

        client.plan(JPEG, PlanRequest("", PlanMode.Recommend))
        val recommend = sentBody()
        client.plan(JPEG, DIRECT)
        val direct = sentBody()

        assertEquals(systemText(recommend), systemText(direct))
        assertEquals(recommend["tools"], direct["tools"])
        assertTrue(systemText(direct).contains("explicit goal in the request weighs ${PlanPriority.EXPLICIT_GOAL}"))
        assertTrue(systemText(direct).contains("names weighs ${PlanPriority.EXPLICIT_OBJECT}"))
        assertTrue(systemText(direct).contains("improvement weighs ${PlanPriority.GENERAL_IMPROVEMENT}"))
        assertEquals(
            listOf(5, 3, 1),
            listOf(PlanPriority.EXPLICIT_GOAL, PlanPriority.EXPLICIT_OBJECT, PlanPriority.GENERAL_IMPROVEMENT),
        )
        val declarations = direct["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(listOf("propose_edit"), declarations.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(
            "ANY",
            direct["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the workflow item names the nine existing tools and nothing else`() = runTest {
        server.enqueue(calls(READY))

        client.plan(JPEG, DIRECT)

        val item = workflowItemSchema(sentBody())
        assertEquals(
            listOf(
                "select_region", "adjust", "adjust_color_range", "erase_selection",
                "cut_out_selection", "fill_selection", "crop_ratio", "apply_style", "topaz_enhance",
            ),
            item["properties"]!!.jsonObject["tool"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val args = item["properties"]!!.jsonObject["args"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(
            listOf(
                "exposure", "contrast", "highlights", "shadows", "temperature",
                "tint", "saturation", "vibrance", "sharpen", "vignette",
            ),
            args["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            HslBand.entries.map { it.name.lowercase() },
            args["color"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            StyleId.entries.map { it.id },
            args["style"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("square", "portrait_3_4", "portrait_4_5", "story_9_16", "landscape_4_3", "landscape_16_9"),
            args["ratio"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse("free is never a ratio", "free" in args["ratio"].toString())
        assertEquals(
            listOf(
                "upscale", "sharpen", "denoise", "restore", "creative",
                "lighting", "color_balance", "colorize", "dust_scratch",
            ),
            args["purpose"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // §15: a local step names its target; nothing is "masked" any more.
        assertNull(args["masked"])
        assertTrue(args.containsKey("target"))
    }

    /**
     * §14: the old instruction's "relative strengths" and its unconditional contrast + saturation
     * example are gone; values are final settings and the evaluation rules are in the instruction.
     */
    @Test
    fun `the instruction keeps observation apart from taste and values final`() {
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("relative strength", ignoreCase = true))
        // §19: the old minimal-change and request-only rules are gone, not merely outweighed.
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("Never propose erase or replace unasked"))
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("Minimal change"))
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("is that one step alone"))
        // §18: the fixed 0.15/0.3 steps and "100 unless" are gone; ranges are per tool.
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("about 0.15 from the current value"))
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("100 unless"))
        assertFalse(PLAN_SYSTEM_INSTRUCTION.contains("enlarging"))
        assertFalse(
            PLAN_SYSTEM_INSTRUCTION.contains(
                "adjust(kind=\"contrast\", value=0.2, masked=false), adjust(kind=\"saturation\"",
            ),
        )
        listOf(
            "An observation alone never decides taste",
            "Silhouettes",
            "no_change",
            "white dress",
            "balloon",
            "green bus",
            "sunset",
            "halos",
            "never to enforce thirds",
            "Every value is a FINAL setting",
            "First identify the photograph's strengths",
            "State one visual objective grounded in the request",
            "Never silently reselect the same subject",
            "Expected effects are hypotheses until the rendered result is compared",
            "no_change is a valid result",
            "no sum of them overrides a hard",
            "needs_clarification",
            "never invent coordinates",
        ).forEach { assertTrue(it, PLAN_SYSTEM_INSTRUCTION.contains(it)) }
    }

    @Test
    fun `a blank key never reaches the wire`() = runTest {
        config = GeminiConfig("", config.baseUrl)

        assertEquals(Result.Failure(AppError.Invalid("no api key")), client.plan(JPEG, DIRECT))
        assertEquals(0, server.requestCount)
    }

    // ---- decoding the envelope -------------------------------------------

    @Test
    fun `a full envelope decodes every field apart`() = runTest {
        server.enqueue(calls(READY))

        val proposal = client.plan(JPEG, DIRECT).valueOrFail()

        assertEquals(
            PlanProposal(
                outcome = PlanOutcome.Ready,
                direction = PlanDirection(
                    subject = "나무와 하늘",
                    strengths = listOf("맑은 빛"),
                    preserve = listOf("하늘 색"),
                    objective = "나무를 더 푸르게",
                    success = "하늘은 그대로이고 나무만 더 푸르다",
                    source = GoalSource.User,
                ),
                observations = listOf(
                    Observation("o1", "나무", "잎이 흐릿한 초록이에요", Confidence.Medium, "실제 색인지 조명인지"),
                ),
                intent = com.diffuse.core.ai.RequestIntent(
                    targets = listOf(IntentItem("나무", explicit = true)),
                    wants = listOf(IntentItem("더 푸르게", explicit = true)),
                    constraints = listOf(Constraint("나무만", ConstraintKind.SubjectOnly, explicit = false)),
                ),
                goals = listOf(PlanGoal("g1", "나무를 더 푸르게", GoalSource.User, GoalScope.Subject, "나무", listOf("o1"))),
                colors = listOf(ColorNote(ColorRole.Emphasize, "초록", "나무")),
                workflow = listOf(
                    com.diffuse.core.ai.WorkflowStep(PlanStep.Select("tree", "n1"), "g1", id = "s1"),
                    com.diffuse.core.ai.WorkflowStep(
                        PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")),
                        "g1",
                        effect = "잎이 더 푸르게",
                        caution = "과하면 인공적",
                        id = "s2",
                    ),
                ),
                limitations = listOf("축소본이라 잎 질감은 확인하기 어려워요"),
            ),
            proposal,
        )
    }

    @Test
    fun `a text part beside the envelope is skipped`() = runTest {
        server.enqueue(calls("""{"text":"<b>네</b> 해드릴게요"}""", READY))

        assertEquals(2, client.plan(JPEG, DIRECT).valueOrFail().workflow.size)
    }

    /** §15: a target is explicit — a subject goal's adjust without one is refused, not guessed. */
    @Test
    fun `a target names the selection, and a subject adjust without one is refused`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal("g1", scope = "subject"), goal("g2")),
                    workflow = listOf(
                        step("select_region", """{"phrase":"face","target":"n1"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2,"target":"n1"}"""),
                        step("adjust", """{"kind":"contrast","value":0.1}""", goal = "g2"),
                    ),
                ),
            ),
        )
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal("g1", scope = "subject")),
                    workflow = listOf(
                        step("select_region", """{"phrase":"face","target":"n1"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2}"""),
                    ),
                ),
            ),
        )

        val steps = client.plan(JPEG, DIRECT).valueOrFail().plan.steps
        val refused = client.plan(JPEG, DIRECT)

        assertEquals(PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.New("n1")), steps[1])
        assertEquals(PlanStep.Adjust(AdjustKind.Contrast, 0.1f), steps[2])
        assertTrue(refused is Result.Failure)
    }

    /** §15: a snapshot ref resolves to that document's mask locally; the model never saw the id. */
    @Test
    fun `a snapshot ref resolves to its own mask, and an unknown ref is refused`() = runTest {
        val context = PlanContext(
            targets = listOf(PlanTargetState("t1", "mask-7", active = true, "얼굴", TargetSource.Confirmed)),
            adjustments = listOf(CurrentAdjust(AdjustKind.Exposure, 0.2f, targetRef = "t1", ref = "a1")),
        )
        val follow = envelope(
            goals = listOf(goal(scope = "subject")),
            workflow = listOf(step("adjust", """{"kind":"exposure","value":0.3,"target":"t1"}""")),
        )
        server.enqueue(calls(follow))
        server.enqueue(calls(follow.replace("\"t1\"", "\"t9\"")))

        val step = client.plan(JPEG, PlanRequest("조금만 더", PlanMode.Direct, context)).valueOrFail().plan.steps.single()
        val stale = client.plan(JPEG, PlanRequest("조금만 더", PlanMode.Direct, context))

        assertEquals(PlanStep.Adjust(AdjustKind.Exposure, 0.3f, PlanTarget.Existing("t1", "mask-7")), step)
        assertEquals(Result.Failure(AppError.Invalid("unplannable:an undeclared target")), stale)
    }

    @Test
    fun `an out-of-range finite value is clamped and the shown value is the clamped one`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    workflow = listOf(
                        step("adjust", """{"kind":"sharpen","value":-0.5}"""),
                        step("adjust", """{"kind":"exposure","value":3}"""),
                    ),
                ),
            ),
        )

        val steps = client.plan(JPEG, DIRECT).valueOrFail().plan.steps

        assertEquals(PlanStep.Adjust(AdjustKind.Sharpen, 0f), steps[0])
        assertEquals(PlanStep.Adjust(AdjustKind.Exposure, 1f), steps[1])
    }

    @Test
    fun `a colour range expands into one Adjust per channel, all for its goal`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "color_range")),
                    workflow = listOf(
                        step("adjust_color_range", """{"color":"blue","hue":0.1,"saturation":0,"luminance":-0.2}"""),
                    ),
                ),
            ),
        )

        val workflow = client.plan(JPEG, DIRECT).valueOrFail().workflow

        assertEquals(
            listOf(
                PlanStep.Adjust(AdjustKind.HslBlueHue, 0.1f),
                PlanStep.Adjust(AdjustKind.HslBlueLuminance, -0.2f),
            ),
            workflow.map { it.step },
        )
        assertTrue(workflow.all { it.goalId == "g1" })
    }

    /**
     * §15, adjust_hsl.md §8: final values. Blue saturation at 0.40 made "조금 더 진하게" is 0.55 in
     * place; a channel left out keeps its value; an explicit 0 resets one that is set.
     */
    @Test
    fun `colour range values are final - left out keeps, an explicit 0 resets`() = runTest {
        val context = PlanContext(
            adjustments = listOf(
                CurrentAdjust(AdjustKind.HslBlueSaturation, 0.4f, ref = "a1"),
                CurrentAdjust(AdjustKind.HslBlueHue, 0.1f, ref = "a2"),
            ),
        )
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "color_range")),
                    workflow = listOf(step("adjust_color_range", """{"color":"blue","saturation":0.55,"hue":0}""")),
                ),
            ),
        )
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "color_range")),
                    workflow = listOf(
                        step("adjust_color_range", """{"color":"blue","saturation":0.5,"masked":true}"""),
                    ),
                ),
            ),
        )

        val steps = client.plan(JPEG, PlanRequest("파랑을 조금 더 진하게", PlanMode.Direct, context))
            .valueOrFail().plan.steps
        val masked = client.plan(JPEG, PlanRequest("파랑을 조금 더 진하게", PlanMode.Direct, context))

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.HslBlueHue, 0f), PlanStep.Adjust(AdjustKind.HslBlueSaturation, 0.55f)),
            steps,
        )
        assertTrue("whole-photo only", masked is Result.Failure)
    }

    @Test
    fun `a crop wherever it arrived runs last`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    workflow = listOf(
                        step("crop_ratio", """{"ratio":"portrait_3_4"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2}"""),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.2f), PlanStep.Crop(CropRatio.Portrait3x4)),
            client.plan(JPEG, DIRECT).valueOrFail().plan.steps,
        )
    }

    /** §19: a Topaz hand-off goes after the crop, and every step keeps the id of its wire position. */
    @Test
    fun `a topaz step runs after the crop, and ids survive the reorder`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    workflow = listOf(
                        step("topaz_enhance", """{"purpose":"denoise"}"""),
                        step("crop_ratio", """{"ratio":"portrait_3_4"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2}"""),
                    ),
                ),
            ),
        )

        val workflow = client.plan(JPEG, DIRECT).valueOrFail().workflow
        assertEquals(
            listOf(PlanStep.Adjust::class, PlanStep.Crop::class, PlanStep.Topaz::class),
            workflow.map { it.step::class },
        )
        assertEquals(listOf("s3", "s2", "s1"), workflow.map { it.id })
    }

    @Test
    fun `a style needs an intensity, and one past the range clamps`() = runTest {
        server.enqueue(calls(envelope(workflow = listOf(step("apply_style", """{"style":"moody-dark"}""")))))
        server.enqueue(
            calls(envelope(workflow = listOf(step("apply_style", """{"style":"vibrant-pop","intensity":250}""")))),
        )

        // §18: no unmeasured "100 unless" default.
        assertEquals(
            Result.Failure(AppError.Invalid("unplannable:style without an intensity")),
            client.plan(JPEG, DIRECT),
        )
        assertEquals(
            listOf(PlanStep.Style(StyleId.VibrantPop, 100)),
            client.plan(JPEG, DIRECT).valueOrFail().plan.steps,
        )
    }

    @Test
    fun `the argument-less tools and a fill decode`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "subject")),
                    workflow = listOf(
                        step("select_region", """{"phrase":"chair","target":"n1"}"""),
                        step("fill_selection", """{"prompt":"a red umbrella","target":"n1"}"""),
                        step("select_region", """{"phrase":"bus","target":"n2"}"""),
                        step("erase_selection", """{"target":"n2"}"""),
                        step("select_region", """{"phrase":"the main subject","target":"n3"}"""),
                        step("cut_out_selection", """{"target":"n3"}"""),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                PlanStep.Select("chair", "n1"), PlanStep.Fill("a red umbrella", "n1"),
                PlanStep.Select("bus", "n2"), PlanStep.Erase("n2"),
                PlanStep.Select("the main subject", "n3"), PlanStep.CutOut("n3"),
            ),
            client.plan(JPEG, DIRECT).valueOrFail().plan.steps,
        )
    }

    /** §14: no_change is a judgement, not a failure: a success with nothing to run. */
    @Test
    fun `no_change with a reason is a successful empty proposal`() = runTest {
        server.enqueue(
            calls(envelope(outcome = "no_change", goals = emptyList(), workflow = emptyList(), extra = REASON)),
        )

        val proposal = client.plan(JPEG, PlanRequest("", PlanMode.Recommend)).valueOrFail()

        assertEquals(PlanOutcome.NoChange, proposal.outcome)
        assertEquals("지금도 균형이 좋아요", proposal.reason)
        assertTrue(proposal.plan.steps.isEmpty())
    }

    @Test
    fun `needs_clarification and unsupported carry their reasons`() = runTest {
        server.enqueue(
            calls(
                envelope(outcome = "needs_clarification", goals = emptyList(), workflow = emptyList(), extra = REASON),
            ),
        )
        server.enqueue(
            calls(envelope(outcome = "unsupported", goals = emptyList(), workflow = emptyList(), extra = REASON)),
        )

        assertEquals(PlanOutcome.NeedsClarification, client.plan(JPEG, DIRECT).valueOrFail().outcome)
        assertEquals(PlanOutcome.Unsupported, client.plan(JPEG, DIRECT).valueOrFail().outcome)
    }

    // ---- §6, row by row --------------------------------------------------

    @Test
    fun `a blocked prompt or a prohibited candidate is Invalid with the blocked prefix`() = runTest {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        server.enqueue(json("""{"candidates":[{"finishReason":"PROHIBITED_CONTENT"}]}"""))

        assertEquals(Result.Failure(AppError.Invalid("blocked:SAFETY")), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.Invalid("blocked:PROHIBITED_CONTENT")), client.plan(JPEG, DIRECT))
    }

    @Test
    fun `status codes map as the eraser's do`() = runTest {
        server.enqueue(error(400, "INVALID_ARGUMENT", "bad image"))
        server.enqueue(error(400, "FAILED_PRECONDITION", "billing"))
        server.enqueue(error(401, "UNAUTHENTICATED", "no key"))
        server.enqueue(error(403, "PERMISSION_DENIED", "denied"))
        server.enqueue(error(404, "NOT_FOUND", "retired"))
        server.enqueue(error(413, "", "too big"))
        listOf(429, 500, 503, 504).forEach { server.enqueue(error(it, "", "busy")) }
        server.enqueue(error(418, "TEAPOT", "short"))

        assertEquals(Result.Failure(AppError.Invalid("bad image")), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.Unavailable), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.Unauthorized), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.Unauthorized), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.Unsupported), client.plan(JPEG, DIRECT))
        assertEquals(Result.Failure(AppError.TooLarge), client.plan(JPEG, DIRECT))
        repeat(4) { assertEquals(Result.Failure(AppError.Unavailable), client.plan(JPEG, DIRECT)) }
        assertTrue((client.plan(JPEG, DIRECT) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `a transport failure and an undecodable body are Io`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json("not json at all"))

        assertTrue((client.plan(JPEG, DIRECT) as Result.Failure).error is AppError.Io)
        assertTrue((client.plan(JPEG, DIRECT) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `cancelling mid-flight closes the call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = async(Dispatchers.IO) { client.plan(JPEG, DIRECT) }
        server.takeRequest()
        job.cancel()

        assertTrue(job.isCancelled)
    }

    // ---- fixtures --------------------------------------------------------

    private fun sentBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun systemText(body: JsonObject) =
        body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun parts(body: JsonObject): JsonArray = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray

    private fun workflowItemSchema(body: JsonObject): JsonObject =
        body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0]
            .jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["workflow"]!!
            .jsonObject["items"]!!.jsonObject

    private fun calls(vararg parts: String) =
        json("""{"candidates":[{"content":{"parts":[${parts.joinToString(",")}]},"finishReason":"STOP"}]}""")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int, status: String, message: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$code,"message":"$message","status":"$status"}}""")

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    companion object {
        const val API_KEY = "AIza-test-key"
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        val JPEG = byteArrayOf(1, 2, 3, 4)
        val DIRECT = PlanRequest(REQUEST, PlanMode.Direct)

        const val REASON = ""","reason":"지금도 균형이 좋아요""""

        fun goal(id: String = "g1", source: String = "user", scope: String = "global", observations: String = "") =
            """{"id":"$id","text":"목표","source":"$source","scope":"$scope","observation_ids":[$observations]}"""

        fun step(tool: String, args: String = "{}", goal: String = "g1") =
            """{"tool":"$tool","goal_id":"$goal","args":$args}"""

        fun constraint(kind: String) =
            ""","intent":{"constraints":[{"text":"조건","kind":"$kind","explicit":true}]}"""

        val ADJUST = step("adjust", """{"kind":"exposure","value":0.2}""")

        fun direction(source: String = "user") =
            ""","direction":{"subject":"주제","objective":"목표","success":"비교 기준","source":"$source"}"""

        /** One `propose_edit` call; [extra] is raw JSON appended inside `args`. */
        fun envelope(
            outcome: String = "ready",
            goals: List<String> = listOf(goal()),
            workflow: List<String> = listOf(ADJUST),
            extra: String = "",
            direction: String = if (outcome == "ready") direction() else "",
        ) = """{"functionCall":{"name":"propose_edit","args":{"outcome":"$outcome",""" +
            """"goals":[${goals.joinToString(",")}],"workflow":[${workflow.joinToString(",")}]$direction$extra}}}"""

        val READY = """
            {"functionCall":{"name":"propose_edit","args":{
              "outcome":"ready",
              "direction":{"subject":"나무와 하늘","strengths":["맑은 빛"],"preserve":["하늘 색"],
                "objective":"나무를 더 푸르게","success":"하늘은 그대로이고 나무만 더 푸르다","source":"user"},
              "observations":[{"id":"o1","subject":"나무","note":"잎이 흐릿한 초록이에요","confidence":"medium","unknown":"실제 색인지 조명인지"}],
              "intent":{"targets":[{"text":"나무","explicit":true}],"wants":[{"text":"더 푸르게","explicit":true}],
                "constraints":[{"text":"나무만","kind":"subject_only","explicit":false}]},
              "goals":[{"id":"g1","text":"나무를 더 푸르게","source":"user","scope":"subject","target":"나무","observation_ids":["o1"]}],
              "color_roles":[{"role":"emphasize","color":"초록","target":"나무"}],
              "workflow":[
                {"tool":"select_region","goal_id":"g1","args":{"phrase":"tree","target":"n1"}},
                {"tool":"adjust","goal_id":"g1","args":{"kind":"saturation","value":0.3,"target":"n1"},
                 "effect":"잎이 더 푸르게","caution":"과하면 인공적"}
              ],
              "limitations":["축소본이라 잎 질감은 확인하기 어려워요"]
            }}}
        """.trimIndent()

        val READY_DEFAULT = envelope(goals = listOf(goal(source = "default")), direction = direction("default"))
    }
}
