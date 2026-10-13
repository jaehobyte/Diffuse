package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.StyleId
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.envelope
import com.diffuse.core.ai.gemini.GeminiPlanClientTest.Companion.step
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
 * `GeminiPlanClientTest` they pushed it past detekt's `LargeClass`, and one tool's tests are a
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
    fun `a requested look decodes to a Style step`() = runTest {
        server.enqueue(
            calls(envelope(workflow = listOf(step("apply_style", """{"style":"film-warm","intensity":100}""")))),
        )

        val plan = client.plan(JPEG, REQUEST).valueOrFail().plan

        assertEquals(listOf(PlanStep.Style(StyleId.FilmWarm, 100)), plan.steps)
    }

    /** §14: atomic now — an unknown id refuses the proposal rather than dropping one step. */
    @Test
    fun `an unknown style refuses the proposal`() = runTest {
        server.enqueue(calls(envelope(workflow = listOf(step("apply_style", """{"style":"sepia-dream"}""")))))

        val error = (client.plan(JPEG, REQUEST) as Result.Failure).error
        assertTrue((error as AppError.Invalid).detail.startsWith(GeminiPlanClient.UNPLANNABLE_PREFIX))
    }

    /** §6's instruction rule, now with §14's: a look is a style and only when it was asked for. */
    @Test
    fun `the instruction tells a look apart from a change`() {
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("$FN_APPLY_STYLE needs a requested look"))
        assertTrue(
            PLAN_SYSTEM_INSTRUCTION.contains("apply_style(style=\"film-warm\", intensity=100), and nothing that"),
        )
        assertTrue(PLAN_SYSTEM_INSTRUCTION.contains("never add an adjust that repeats what a style already does"))
    }

    private fun calls(vararg parts: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"candidates":[{"content":{"parts":[${parts.joinToString(",")}]}}]}""")

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val API_KEY = "AIza-test-key"
        val REQUEST = PlanRequest("필름 느낌으로 바꿔줘", PlanMode.Direct)
        val JPEG = byteArrayOf(1, 2, 3, 4)
    }
}
