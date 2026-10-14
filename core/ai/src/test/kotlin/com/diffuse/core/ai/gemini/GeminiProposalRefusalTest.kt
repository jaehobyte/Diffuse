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
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanGoal
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.StyleId
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
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.ADJUST
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.API_KEY
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.DIRECT
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.JPEG
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.READY
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.REASON
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.constraint
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.direction
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.envelope
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.goal
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/vibe_edit.md §14: the envelope is refused **whole**. Split from `GeminiPlanClientTest`
 * for detekt's `LargeClass`; the harness is the same four lines.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiProposalRefusalTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiPlanClient

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = GeminiConfig(API_KEY, server.url("/").toString().trimEnd('/'))
        client = GeminiPlanClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- refusing the whole envelope -------------------------------------

    @Test
    fun `no function call, two envelopes or another function are refused`() = runTest {
        server.enqueue(calls("""{"text":"I cannot."}"""))
        server.enqueue(calls(READY, READY))
        server.enqueue(calls("""{"functionCall":{"name":"select_region","args":{"phrase":"tree"}}}"""))

        repeat(3) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    @Test
    fun `a malformed field anywhere refuses the whole proposal`() = runTest {
        val malformed = listOf(
            // an unknown tool
            envelope(workflow = listOf(step("adjust", """{"kind":"exposure","value":0.2}"""), step("denoise"))),
            // an unknown kind, and a kind the catalog keeps closed
            envelope(workflow = listOf(step("adjust", """{"kind":"brightness","value":0.2}"""))),
            envelope(workflow = listOf(step("adjust", """{"kind":"clarity","value":0.2}"""))),
            // a non-finite and a non-numeric value
            envelope(workflow = listOf(step("adjust", """{"kind":"exposure","value":"NaN"}"""))),
            envelope(workflow = listOf(step("adjust", """{"kind":"exposure","value":"lots"}"""))),
            // a missing value, a blank phrase, an unknown ratio, style, colour
            envelope(workflow = listOf(step("adjust", """{"kind":"exposure"}"""))),
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(
                    step("select_region", """{"phrase":" ","target":"n1"}"""),
                    step("erase_selection", """{"target":"n1"}"""),
                ),
            ),
            envelope(workflow = listOf(step("crop_ratio", """{"ratio":"free"}"""))),
            envelope(workflow = listOf(step("apply_style", """{"style":"sepia-dream","intensity":50}"""))),
            // a select with no label, an erase with no target, a topaz purpose that does not exist
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(step("select_region", """{"phrase":"bus"}"""), step("erase_selection")),
            ),
            envelope(workflow = listOf(step("topaz_enhance", """{"purpose":"remove_background"}"""))),
            envelope(workflow = listOf(step("topaz_enhance", """{"purpose":"upscale","scale":2.5}"""))),
            envelope(
                goals = listOf(goal(scope = "color_range")),
                workflow = listOf(step("adjust_color_range", """{"color":"teal","hue":0.2}""")),
            ),
            // a colour range that changes nothing
            envelope(
                goals = listOf(goal(scope = "color_range")),
                workflow = listOf(step("adjust_color_range", """{"color":"blue","hue":0}""")),
            ),
            // an observation missing its note, an unknown confidence, an unknown outcome
            envelope(extra = ""","observations":[{"id":"o1","subject":"하늘","confidence":"high"}]"""),
            envelope(extra = ""","observations":[{"id":"o1","subject":"하늘","note":"밝다","confidence":"sure"}]"""),
            envelope(outcome = "maybe"),
        )
        malformed.forEach { server.enqueue(calls(it)) }

        malformed.forEach { assertUnplannable(client.plan(JPEG, DIRECT), it) }
    }

    @Test
    fun `a dangling goal or observation id refuses the proposal`() = runTest {
        server.enqueue(
            calls(envelope(workflow = listOf(step("adjust", """{"kind":"exposure","value":0.2}""", goal = "g9")))),
        )
        server.enqueue(calls(envelope(goals = listOf(goal(observations = "\"o7\"")), workflow = listOf(ADJUST))))
        server.enqueue(calls(envelope(goals = listOf(goal(), goal()), workflow = listOf(ADJUST))))

        repeat(3) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    @Test
    fun `text past its cap and lists past theirs are refused`() = runTest {
        val long = "가".repeat(ProposalLimits.TEXT_CHARS + 1)
        val observation = """{"id":"o%d","subject":"하늘","note":"밝다","confidence":"low"}"""
        server.enqueue(calls(envelope(extra = ""","limitations":["$long"]""")))
        server.enqueue(
            calls(envelope(extra = ""","observations":[${(1..9).joinToString(",") { observation.format(it) }}]""")),
        )
        server.enqueue(calls(envelope(workflow = List(ProposalLimits.WORKFLOW + 1) { ADJUST })))
        server.enqueue(calls(envelope(extra = ""","limitations":[${List(7) { "\"제한\"" }.joinToString(",")}]""")))

        repeat(4) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    @Test
    fun `an outcome that contradicts its workflow is refused`() = runTest {
        // ready with nothing to run, ready with only a selection, no_change with steps, no reason
        server.enqueue(calls(envelope(workflow = emptyList())))
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "subject")),
                    workflow = listOf(step("select_region", """{"phrase":"face","target":"n1"}""")),
                ),
            ),
        )
        server.enqueue(calls(envelope(outcome = "no_change", workflow = listOf(ADJUST), extra = REASON)))
        server.enqueue(calls(envelope(outcome = "no_change", goals = emptyList(), workflow = emptyList())))

        repeat(4) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    @Test
    fun `a goal the user never asked for may not claim the request`() = runTest {
        server.enqueue(calls(READY))

        assertUnplannable(client.plan(JPEG, PlanRequest("", PlanMode.Recommend)))
    }

    /**
     * §19 (replaces §14's "a look, a shape, a removal or a fill needs a request"): a default goal
     * may propose any of them — the user turns off what they do not want. A source is never
     * rewritten to `user` to get there.
     */
    @Test
    fun `a default goal may style, crop, erase, fill or hand off to topaz`() = runTest {
        val unrequested = listOf(
            listOf(step("apply_style", """{"style":"film-warm","intensity":50}""")),
            listOf(step("crop_ratio", """{"ratio":"portrait_4_5"}""")),
            listOf(
                step("select_region", """{"phrase":"trash can","target":"n1"}"""),
                step("erase_selection", """{"target":"n1"}"""),
            ),
            listOf(
                step("select_region", """{"phrase":"sign","target":"n1"}"""),
                step("fill_selection", """{"prompt":"a bush","target":"n1"}"""),
            ),
            listOf(ADJUST, step("topaz_enhance", """{"purpose":"denoise"}""")),
            listOf(step("topaz_enhance", """{"purpose":"colorize"}""")),
            listOf(step("topaz_enhance", """{"purpose":"sharpen","face_recovery":true}""")),
        ).map { workflow ->
            val scope = if (workflow.any { "select_region" in it }) "subject" else "global"
            envelope(
                goals = listOf(goal(source = "default", scope = scope)),
                workflow = workflow,
                direction = direction("default"),
            )
        }
        unrequested.forEach { server.enqueue(calls(it)) }

        unrequested.forEach { case ->
            val result = client.plan(JPEG, PlanRequest("", PlanMode.Recommend))
            val proposal = (result as? Result.Success)?.value
            assertTrue("expected $case to be accepted, got $result", proposal != null)
            assertTrue(proposal!!.goals.all { it.source == GoalSource.Default })
        }
    }

    /** §19: what the user said to keep still refuses a default suggestion that would break it. */
    @Test
    fun `an explicit constraint still refuses a default suggestion`() = runTest {
        val broken = listOf(
            envelope(
                goals = listOf(goal(source = "default")),
                workflow = listOf(step("apply_style", """{"style":"film-warm","intensity":50}""")),
                extra = constraint("keep_background"),
                direction = direction("default"),
            ),
            envelope(
                goals = listOf(goal(source = "default")),
                workflow = listOf(step("topaz_enhance", """{"purpose":"colorize"}""")),
                extra = constraint("keep_colors"),
                direction = direction("default"),
            ),
            envelope(
                goals = listOf(goal(source = "default")),
                workflow = listOf(step("crop_ratio", """{"ratio":"square"}""")),
                extra = constraint("keep_frame"),
                direction = direction("default"),
            ),
        )
        broken.forEach { server.enqueue(calls(it)) }

        broken.forEach { assertUnplannable(client.plan(JPEG, PlanRequest("", PlanMode.Recommend)), it) }
    }

    @Test
    fun `a step outside its goal's scope is refused`() = runTest {
        val outside = listOf(
            // a subject goal that adjusts the whole photo
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(
                    step("select_region", """{"phrase":"face","target":"n1"}"""),
                    step("adjust", """{"kind":"exposure","value":0.3,"masked":false}"""),
                ),
            ),
            // a global goal with a masked adjust
            envelope(workflow = listOf(step("adjust", """{"kind":"exposure","value":0.3,"masked":true}"""))),
            // a colour range goal using a plain adjust
            envelope(goals = listOf(goal(scope = "color_range")), workflow = listOf(ADJUST)),
            // a subject goal with a style
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(step("apply_style", """{"style":"film-warm","intensity":50}""")),
            ),
            // a subject goal with a Topaz step
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(step("topaz_enhance", """{"purpose":"lighting"}""")),
            ),
        )
        outside.forEach { server.enqueue(calls(it)) }

        outside.forEach { assertUnplannable(client.plan(JPEG, DIRECT), it) }
    }

    /**
     * §15: a local step names its selection. Without a name it is not local — and in a subject
     * goal that is refused, never resolved to whatever was selected last; an undeclared name is
     * refused too.
     */
    @Test
    fun `a local step names a declared target`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal("g1", scope = "subject"), goal("g2", scope = "subject")),
                    workflow = listOf(
                        step("select_region", """{"phrase":"dress","target":"n1"}"""),
                        step("adjust", """{"kind":"highlights","value":-0.2,"target":"n1"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2,"target":"n2"}""", goal = "g2"),
                    ),
                ),
            ),
        )
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal(scope = "subject")),
                    workflow = listOf(step("adjust", """{"kind":"exposure","value":0.2}""")),
                ),
            ),
        )

        repeat(2) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    @Test
    fun `a selection nothing uses is refused`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    goals = listOf(goal("g1", scope = "subject"), goal("g2")),
                    workflow = listOf(
                        step("select_region", """{"phrase":"face","target":"n1"}"""),
                        step("adjust", """{"kind":"exposure","value":0.2}""", goal = "g2"),
                    ),
                ),
            ),
        )

        assertUnplannable(client.plan(JPEG, DIRECT))
    }

    @Test
    fun `hard constraints refuse the steps they rule out`() = runTest {
        val broken = listOf(
            // subject only, then a global colour range
            envelope(
                goals = listOf(goal("g1", scope = "subject"), goal("g2", scope = "color_range")),
                workflow = listOf(
                    step("select_region", """{"phrase":"green bus","target":"n1"}"""),
                    step("adjust", """{"kind":"contrast","value":0.15,"target":"n1"}"""),
                    step("adjust_color_range", """{"color":"green","saturation":0.3}""", goal = "g2"),
                ),
                extra = constraint("subject_only"),
            ),
            // keep the background, then a style
            envelope(
                workflow = listOf(step("apply_style", """{"style":"pastel-soft","intensity":50}""")),
                extra = constraint("keep_background"),
            ),
            // keep the background, then a cut-out — it deletes the outside
            envelope(
                goals = listOf(goal(scope = "subject")),
                workflow = listOf(
                    step("select_region", """{"phrase":"person","target":"n1"}"""),
                    step("cut_out_selection", """{"target":"n1"}"""),
                ),
                extra = constraint("keep_background"),
            ),
            // keep the colours, then saturation
            envelope(
                workflow = listOf(step("adjust", """{"kind":"saturation","value":0.2}""")),
                extra = constraint("keep_colors"),
            ),
        )
        broken.forEach { server.enqueue(calls(it)) }

        broken.forEach { assertUnplannable(client.plan(JPEG, DIRECT), it) }
    }

    @Test
    fun `two styles or two crops are refused`() = runTest {
        server.enqueue(
            calls(
                envelope(
                    workflow = listOf(
                        step("apply_style", """{"style":"film-warm","intensity":50}"""),
                        step("apply_style", """{"style":"moody-dark","intensity":50}"""),
                    ),
                ),
            ),
        )
        server.enqueue(
            calls(
                envelope(
                    workflow = listOf(
                        step("crop_ratio", """{"ratio":"square"}"""),
                        step("crop_ratio", """{"ratio":"story_9_16"}"""),
                    ),
                ),
            ),
        )

        repeat(2) { assertUnplannable(client.plan(JPEG, DIRECT)) }
    }

    private fun assertUnplannable(result: Result<PlanProposal>, case: String = "") {
        val detail = ((result as? Result.Failure)?.error as? AppError.Invalid)?.detail
        assertTrue(
            "expected a refusal for $case, got $result",
            detail?.startsWith(GeminiPlanClient.UNPLANNABLE_PREFIX) == true,
        )
    }

    private fun calls(vararg parts: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"candidates":[{"content":{"parts":[${parts.joinToString(",")}]},"finishReason":"STOP"}]}""")
}
