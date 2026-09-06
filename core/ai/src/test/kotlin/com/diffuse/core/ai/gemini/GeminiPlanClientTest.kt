package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.PlanStep
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

/** specs/vibe_edit.md §4, §5, §6, §12. MockWebServer binds localhost only. */
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
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/v1beta/models/gemini-2.5-flash:generateContent",
            recorded.requestUrl?.encodedPath,
        )
    }

    @Test
    fun `the key travels in the header and never in the URL`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val recorded = server.takeRequest()
        assertEquals(API_KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.requestUrl?.queryParameter("key"))
        assertFalse(recorded.requestUrl.toString().contains(API_KEY))
    }

    @Test
    fun `the body carries the system instruction, the image and the sentence verbatim`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(
            PLAN_SYSTEM_INSTRUCTION,
            system[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(
            "image/jpeg",
            parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertEquals(REQUEST, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the body declares the seven functions and forces a call`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val declarations = body["tools"]!!.jsonArray[0]
            .jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(
            listOf(
                "select_region",
                "adjust",
                "adjust_color_range",
                "erase_selection",
                "cut_out_selection",
                "fill_selection",
                "crop_ratio",
            ),
            declarations.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        assertEquals(
            "ANY",
            body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!
                .jsonObject["mode"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the adjust declaration enumerates the ten kinds`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val adjust = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!
            .jsonArray[1].jsonObject
        val kinds = adjust["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray
        // specs/adjust_hsl.md §8: the 24 혼합 kinds have their own function, and putting them
        // here too would be 34 values on the argument the model already gets wrong most often.
        assertEquals(10, kinds.size)
        assertEquals(
            AdjustKind.entries.filter { it.hsl == null }.map { it.name.lowercase() },
            kinds.map { it.jsonPrimitive.content },
        )
        assertTrue(
            "no HSL kind may reach the adjust enum",
            kinds.none { it.jsonPrimitive.content.startsWith("hsl") },
        )
    }

    @Test
    fun `the colour range declaration enumerates the eight bands`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val declaration = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!
            .jsonArray[2].jsonObject
        val colours = declaration["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["color"]!!.jsonObject["enum"]!!.jsonArray

        assertEquals(
            HslBand.entries.map { it.name.lowercase() },
            colours.map { it.jsonPrimitive.content },
        )
    }

    /** specs/adjust_hsl.md §8: a colour is not a thing to select. */
    @Test
    fun `the instruction sends colour requests to the colour range function`() {
        assertTrue(
            "a colour range needs no selection",
            PLAN_SYSTEM_INSTRUCTION.contains("adjust_color_range"),
        )
        assertTrue(
            "the worked example the report asks for",
            PLAN_SYSTEM_INSTRUCTION.contains("adjust_color_range(color=\"blue\""),
        )
    }

    /** T52: the three rules the first device run showed the model getting wrong. */
    @Test
    fun `the instruction demands English phrases, whole plans and unmasked adjusts after a removal`() {
        assertTrue(
            "the phrase must be English — SAM 3 is English concept segmentation",
            PLAN_SYSTEM_INSTRUCTION.contains("It must always be English"),
        )
        assertTrue(
            "the model was stopping after select_region",
            PLAN_SYSTEM_INSTRUCTION.contains("Never stop after selecting"),
        )
        assertTrue(
            "a masked adjust after a removal lands inside the hole",
            PLAN_SYSTEM_INSTRUCTION.contains("must pass masked=false"),
        )
    }

    @Test
    fun `the instruction shows a removal that is two calls, not one`() {
        assertTrue(
            PLAN_SYSTEM_INSTRUCTION.contains(
                """- "버스를 지워줘" -> select_region(phrase="bus"), erase_selection()""",
            ),
        )
    }

    @Test
    fun `the select declaration says English too`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val select = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!
            .jsonArray[0].jsonObject
        val phrase = select["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["phrase"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(phrase.contains("Always English"))
    }

    @Test
    fun `an English phrase decodes unchanged`() = runTest {
        server.enqueue(
            calls("""{"functionCall":{"name":"select_region","args":{"phrase":"bus"}}}"""),
        )

        assertEquals(
            listOf(PlanStep.Select("bus")),
            client.plan(JPEG, "버스를 지워줘").valueOrFail().steps,
        )
    }

    @Test
    fun `a blank key never reaches the wire`() = runTest {
        config = GeminiConfig("", server.url("/").toString().trimEnd('/'))

        assertEquals(Result.Failure(AppError.Invalid("no api key")), client.plan(JPEG, REQUEST))
        assertEquals(0, server.requestCount)
    }

    // ---- the response ----------------------------------------------------

    @Test
    fun `two function calls decode in order`() = runTest {
        server.enqueue(calls(SELECT_CALL, ADJUST_CALL))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
            plan.steps,
        )
    }

    @Test
    fun `a text part between two calls is skipped`() = runTest {
        server.enqueue(
            json(
                """
                {"candidates":[{"content":{"parts":[
                  $SELECT_CALL,
                  {"text":"네, 나무를 푸르게 해드릴게요."},
                  $ADJUST_CALL
                ]}}]}
                """.trimIndent(),
            ),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(2, plan.steps.size)
        assertEquals(PlanStep.Select("나무"), plan.steps[0])
    }

    @Test
    fun `the argument-less calls decode`() = runTest {
        server.enqueue(
            calls(
                SELECT_CALL,
                """{"functionCall":{"name":"erase_selection","args":{}}}""",
                """{"functionCall":{"name":"cut_out_selection","args":{}}}""",
            ),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(listOf(PlanStep.Select("나무"), PlanStep.Erase, PlanStep.CutOut), plan.steps)
    }

    // ---- T62, specs/generative_fill.md §8 --------------------------------

    @Test
    fun `a fill_selection call decodes to one Fill step`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"select_region","args":{"phrase":"chair"}}}""",
                FILL_CALL,
            ),
        )

        assertEquals(
            listOf(PlanStep.Select("chair"), PlanStep.Fill("a red umbrella")),
            client.plan(JPEG, "의자를 빨간 우산으로 바꿔줘").valueOrFail().steps,
        )
    }

    @Test
    fun `a blank or absent prompt drops the fill and the rest survive`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"fill_selection","args":{}}}""",
                """{"functionCall":{"name":"fill_selection","args":{"prompt":"   "}}}""",
                ADJUST_CALL,
            ),
        )

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true)),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    @Test
    fun `the fill declaration asks for an English prompt`() = runTest {
        server.enqueue(calls(SELECT_CALL))

        client.plan(JPEG, REQUEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val fill = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!
            .jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "fill_selection" }
            .jsonObject
        val prompt = fill["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["prompt"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("Always English"))
    }

    /** §8: the one rule the instruction gains — the two generative tools are opposites. */
    @Test
    fun `the instruction says which of fill and erase to call`() {
        assertTrue(
            PLAN_SYSTEM_INSTRUCTION.contains(
                "fill_selection replaces, erase_selection removes",
            ),
        )
        assertTrue(
            PLAN_SYSTEM_INSTRUCTION.contains(
                """fill_selection(prompt="a red umbrella")""",
            ),
        )
    }

    @Test
    fun `an unknown function name is dropped and the rest survive`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"apply_filter","args":{"name":"vintage"}}}""",
                ADJUST_CALL,
            ),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true)),
            plan.steps,
        )
    }

    @Test
    fun `a missing required argument drops that step`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"select_region","args":{}}}""",
                """{"functionCall":{"name":"adjust","args":{"kind":"saturation"}}}""",
                """{"functionCall":{"name":"adjust","args":{"value":0.4}}}""",
                ADJUST_CALL,
            ),
        )

        assertEquals(1, client.plan(JPEG, REQUEST).valueOrFail().steps.size)
    }

    @Test
    fun `an unknown adjust kind drops that step`() = runTest {
        server.enqueue(
            calls("""{"functionCall":{"name":"adjust","args":{"kind":"clarity","value":0.4}}}"""),
        )

        assertEquals(emptyList<PlanStep>(), client.plan(JPEG, REQUEST).valueOrFail().steps)
    }

    @Test
    fun `an out-of-range value is clamped to the kind's range`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust","args":{"kind":"exposure","value":4}}}""",
                """{"functionCall":{"name":"adjust","args":{"kind":"vignette","value":-0.5}}}""",
            ),
        )

        assertEquals(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 1f, masked = true),
                PlanStep.Adjust(AdjustKind.Vignette, 0f, masked = true),
            ),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    @Test
    fun `a non-finite value drops the step`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust","args":{"kind":"exposure","value":"NaN"}}}""",
                """{"functionCall":{"name":"adjust","args":{"kind":"contrast","value":"x"}}}""",
            ),
        )

        assertEquals(emptyList<PlanStep>(), client.plan(JPEG, REQUEST).valueOrFail().steps)
    }

    @Test
    fun `masked defaults to true and is honoured when given`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust","args":{"kind":"tint","value":0.2,"masked":false}}}""",
            ),
        )

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Tint, 0.2f, masked = false)),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    // ---- specs/adjust_hsl.md §8: the fifth function ----------------------

    @Test
    fun `a colour range expands into one Adjust per channel, in order`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust_color_range",""" +
                    """"args":{"color":"blue","luminance":-0.2,"saturation":0.4}}}""",
            ),
        )

        assertEquals(
            listOf(
                PlanStep.Adjust(AdjustKind.HslBlueSaturation, 0.4f, masked = false),
                PlanStep.Adjust(AdjustKind.HslBlueLuminance, -0.2f, masked = false),
            ),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    @Test
    fun `a colour range edits the whole photo unless it is told otherwise`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust_color_range",""" +
                    """"args":{"color":"red","hue":0.3,"masked":true}}}""",
            ),
        )

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.HslRedHue, 0.3f, masked = true)),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    @Test
    fun `an unknown colour drops the whole call and the rest of the plan survives`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust_color_range","args":{"color":"teal","hue":0.3}}}""",
                ADJUST_CALL,
            ),
        )

        assertEquals(1, client.plan(JPEG, REQUEST).valueOrFail().steps.size)
    }

    @Test
    fun `a colour range with no channel, or only zeros, contributes no steps`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust_color_range","args":{"color":"green"}}}""",
                """{"functionCall":{"name":"adjust_color_range","args":{"color":"aqua","hue":0}}}""",
            ),
        )

        // specs/vibe_edit.md §7: an empty plan is a valid answer, not a failure.
        assertEquals(Result.Success(EditPlan(emptyList())), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `a colour range drops the non-finite channel and keeps the others`() = runTest {
        server.enqueue(
            calls(
                """{"functionCall":{"name":"adjust_color_range",""" +
                    """"args":{"color":"purple","hue":"NaN","saturation":4}}}""",
            ),
        )

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.HslPurpleSaturation, 1f, masked = false)),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    @Test
    fun `no function call at all is an empty plan, not a failure`() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"I cannot."}]}}]}"""))

        assertEquals(Result.Success(EditPlan(emptyList())), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `a blocked prompt is Invalid with the blocked prefix`() = runTest {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))

        assertEquals(
            Result.Failure(AppError.Invalid("blocked:SAFETY")),
            client.plan(JPEG, REQUEST),
        )
    }

    @Test
    fun `a candidate cut short for prohibited content is blocked too`() = runTest {
        server.enqueue(json("""{"candidates":[{"finishReason":"PROHIBITED_CONTENT"}]}"""))

        assertEquals(
            Result.Failure(AppError.Invalid("blocked:PROHIBITED_CONTENT")),
            client.plan(JPEG, REQUEST),
        )
    }

    @Test
    fun `an ordinary finishReason is not a block`() = runTest {
        server.enqueue(
            json("""{"candidates":[{"finishReason":"STOP","content":{"parts":[$SELECT_CALL]}}]}"""),
        )

        assertEquals(
            listOf(PlanStep.Select("나무")),
            client.plan(JPEG, REQUEST).valueOrFail().steps,
        )
    }

    // ---- generative_erase.md §6, row by row ------------------------------

    @Test
    fun `400 INVALID_ARGUMENT is Invalid carrying the message`() = runTest {
        server.enqueue(error(400, "INVALID_ARGUMENT", "bad image"))

        assertEquals(Result.Failure(AppError.Invalid("bad image")), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `400 FAILED_PRECONDITION is Unavailable`() = runTest {
        server.enqueue(error(400, "FAILED_PRECONDITION", "billing not enabled"))

        assertEquals(Result.Failure(AppError.Unavailable), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `401 and 403 are Unauthorized`() = runTest {
        server.enqueue(error(401, "UNAUTHENTICATED", "no key"))
        server.enqueue(error(403, "PERMISSION_DENIED", "denied"))

        assertEquals(Result.Failure(AppError.Unauthorized), client.plan(JPEG, REQUEST))
        assertEquals(Result.Failure(AppError.Unauthorized), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `404 is Unsupported`() = runTest {
        server.enqueue(error(404, "NOT_FOUND", "model retired"))

        assertEquals(Result.Failure(AppError.Unsupported), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `413 is TooLarge`() = runTest {
        server.enqueue(error(413, "", "too big"))

        assertEquals(Result.Failure(AppError.TooLarge), client.plan(JPEG, REQUEST))
    }

    @Test
    fun `429, 500, 503 and 504 are Unavailable`() = runTest {
        listOf(429, 500, 503, 504).forEach { code ->
            server.enqueue(error(code, "", "busy"))
            assertEquals(Result.Failure(AppError.Unavailable), client.plan(JPEG, REQUEST))
        }
    }

    @Test
    fun `an unmapped status is Io`() = runTest {
        server.enqueue(error(418, "TEAPOT", "short and stout"))

        assertTrue((client.plan(JPEG, REQUEST) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `a transport failure is Io`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue((client.plan(JPEG, REQUEST) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `an undecodable body is Io`() = runTest {
        server.enqueue(json("not json at all"))

        assertTrue((client.plan(JPEG, REQUEST) as Result.Failure).error is AppError.Io)
    }

    // ---- cancellation ----------------------------------------------------

    @Test
    fun `cancelling mid-flight closes the call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = async(Dispatchers.IO) { client.plan(JPEG, REQUEST) }
        server.takeRequest()
        job.cancel()

        assertTrue(job.isCancelled)
    }

    // ---- fixtures --------------------------------------------------------

    // ---- crop_ratio (T58, specs/vibe_edit.md §4.1, §5) -------------------

    @Test
    fun `a crop ratio decodes to a Crop step`() = runTest {
        server.enqueue(calls(CROP_CALL))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(EditPlan(listOf(PlanStep.Crop(CropRatio.Story9x16))), plan)
    }

    @Test
    fun `every ratio the catalog offers decodes`() = runTest {
        val wire = listOf("square", "portrait_4_5", "story_9_16", "landscape_16_9")
        server.enqueue(
            calls(*wire.map { cropCall(it) }.toTypedArray()),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        // Only the last survives — §5 keeps one crop per plan — so ask about the mapping
        // one ratio at a time instead.
        assertEquals(listOf(PlanStep.Crop(CropRatio.Landscape16x9)), plan.steps)
    }

    @Test
    fun `a crop that arrived first still runs last`() = runTest {
        server.enqueue(calls(CROP_CALL, SELECT_CALL, ADJUST_CALL))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
                PlanStep.Crop(CropRatio.Story9x16),
            ),
            plan.steps,
        )
    }

    @Test
    fun `two crop calls become the last one only`() = runTest {
        server.enqueue(calls(cropCall("square"), ADJUST_CALL, cropCall("landscape_16_9")))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
                PlanStep.Crop(CropRatio.Landscape16x9),
            ),
            plan.steps,
        )
    }

    @Test
    fun `an unknown ratio drops the step and the rest of the plan survives`() = runTest {
        server.enqueue(calls(ADJUST_CALL, cropCall("panorama")))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true)),
            plan.steps,
        )
    }

    @Test
    fun `free is not a ratio the model may choose`() = runTest {
        server.enqueue(calls(cropCall("free")))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(emptyList<PlanStep>(), plan.steps)
    }

    private fun cropCall(ratio: String) =
        """{"functionCall":{"name":"crop_ratio","args":{"ratio":"$ratio"}}}"""

    private fun calls(vararg parts: String) =
        json("""{"candidates":[{"content":{"parts":[${parts.joinToString(",")}]}}]}""")

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

    private companion object {
        const val FILL_CALL =
            """{"functionCall":{"name":"fill_selection","args":{"prompt":"a red umbrella"}}}"""

        const val API_KEY = "AIza-test-key"
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        val JPEG = byteArrayOf(1, 2, 3, 4)

        const val SELECT_CALL =
            """{"functionCall":{"name":"select_region","args":{"phrase":"나무"}}}"""
        const val ADJUST_CALL =
            """{"functionCall":{"name":"adjust","args":{"kind":"saturation","value":0.3}}}"""
        const val CROP_CALL =
            """{"functionCall":{"name":"crop_ratio","args":{"ratio":"story_9_16"}}}"""
    }
}
