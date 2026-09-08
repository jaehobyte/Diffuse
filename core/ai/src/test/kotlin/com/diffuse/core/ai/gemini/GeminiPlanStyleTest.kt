package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.StyleId
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/style_match.md §6, §9. `apply_style`'s own tests, in their own class: added to
 * `GeminiPlanClientTest` they pushed it past detekt's `LargeClass`, and one function's tests are a
 * clean seam — the harness below is the four lines that file also starts with.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiPlanStyleTest {

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

    @Test
    fun `a style decodes to a Style step`() = runTest {
        server.enqueue(calls(STYLE_CALL))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(EditPlan(listOf(PlanStep.Style(StyleId.FilmWarm, 100))), plan)
    }

    /** §6: the model names a look, so the enum is closed and it is the catalog's own ids. */
    @Test
    fun `every catalogued id is on the wire, spelled as the catalog spells it`() = runTest {
        server.enqueue(calls(STYLE_CALL))

        client.plan(JPEG, REQUEST)

        val ids = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            .let { it["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray }
            .single { it.jsonObject["name"]!!.jsonPrimitive.content == "apply_style" }
            .jsonObject["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["style"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(StyleId.entries.map { it.id }, ids)
        assertTrue("film-warm" in ids)
    }

    /** §6: 강도 is optional — a look named with no strength means all of it. */
    @Test
    fun `a style with no intensity is the whole style`() = runTest {
        server.enqueue(
            calls("""{"functionCall":{"name":"apply_style","args":{"style":"moody-dark"}}}"""),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(listOf(PlanStep.Style(StyleId.MoodyDark, 100)), plan.steps)
    }

    @Test
    fun `an intensity outside the range clamps rather than dropping the step`() = runTest {
        server.enqueue(calls(styleCall("vibrant-pop", "250")))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(listOf(PlanStep.Style(StyleId.VibrantPop, 100)), plan.steps)
    }

    /** §5, §6: an unknown id drops **that** step, and the steps after it survive. */
    @Test
    fun `an unknown style drops its step and keeps the rest`() = runTest {
        server.enqueue(calls(styleCall("sepia-dream", "80"), ADJUST_CALL))

        val plan = client.plan(JPEG, REQUEST).valueOrFail()

        assertEquals(
            listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true)),
            plan.steps,
        )
    }

    /** §6's one instruction rule: a look is a style, a change stays an adjust. */
    @Test
    fun `the instruction tells a look apart from a change`() {
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("is apply_style"))
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("stays adjust"))
        assertTrue(
            PLAN_SYSTEM_INSTRUCTION.contains("apply_style(style=\"film-warm\", intensity=100)"),
        )
    }

    private fun styleCall(style: String, intensity: String) =
        """{"functionCall":{"name":"apply_style","args":{"style":"$style","intensity":$intensity}}}"""

    private fun calls(vararg parts: String) =
        json("""{"candidates":[{"content":{"parts":[${parts.joinToString(",")}]}}]}""")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val API_KEY = "AIza-test-key"
        const val REQUEST = "필름 느낌으로 바꿔줘"
        val JPEG = byteArrayOf(1, 2, 3, 4)

        const val STYLE_CALL =
            """{"functionCall":{"name":"apply_style","args":{"style":"film-warm","intensity":100}}}"""
        const val ADJUST_CALL =
            """{"functionCall":{"name":"adjust","args":{"kind":"saturation","value":0.3}}}"""
    }
}
