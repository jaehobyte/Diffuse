package com.diffuse.core.ai.gemini

import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.SuggestionCapability
import com.diffuse.core.ai.StyleId
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
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
import java.util.concurrent.TimeUnit

/** specs/vibe_edit.md §14. MockWebServer binds localhost only; no test reaches the real API. */
@RunWith(RobolectricTestRunner::class)
class GeminiSuggestionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiSuggestionClient
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
        client = GeminiSuggestionClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the request -----------------------------------------------------

    @Test
    fun `a suggestion request posts the image and the catalog, key in the header only`() = runTest {
        server.enqueue(ids("\"warm\""))

        client.suggest(JPEG)

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", recorded.requestUrl?.encodedPath)
        assertEquals(API_KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.requestUrl?.queryParameter("key"))
        assertFalse(recorded.requestUrl.toString().contains(API_KEY))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals(
            "image/jpeg",
            parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertEquals(SUGGESTION_CATALOG, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        PromptSuggestionId.entries.forEach { assertTrue(it.wire, SUGGESTION_CATALOG.contains(it.wire)) }
    }

    @Test
    fun `the suggestion function is the only one and its ids are the closed catalog`() = runTest {
        server.enqueue(ids("\"warm\""))

        client.suggest(JPEG)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val declarations = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, declarations.size)
        val declaration = declarations[0].jsonObject
        assertEquals("suggest_directions", declaration["name"]!!.jsonPrimitive.content)
        val enum = declaration["parameters"]!!.jsonObject["properties"]!!.jsonObject["ids"]!!
            .jsonObject["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(PromptSuggestionId.entries.map { it.wire }, enum)
        assertEquals(
            "ANY",
            body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the suggestion instruction keeps dark photos and forbids opposites`() {
        assertTrue(SUGGESTION_SYSTEM_INSTRUCTION.contains("may be dark on purpose"))
        assertTrue(SUGGESTION_SYSTEM_INSTRUCTION.contains("never two opposites"))
        assertTrue(SUGGESTION_SYSTEM_INSTRUCTION.contains("Never invent"))
    }

    @Test
    fun `no key means no suggestion request`() = runTest {
        config = GeminiConfig("", config.baseUrl)

        assertEquals(Result.Failure(AppError.Invalid("no api key")), client.suggest(JPEG))
        assertEquals(0, server.requestCount)
    }

    // ---- the answer ------------------------------------------------------

    @Test
    fun `suggestion ids keep the model's order`() = runTest {
        server.enqueue(ids("\"vivid_color\"", "\"lift_shadows\"", "\"cool\""))

        assertEquals(
            listOf(PromptSuggestionId.VividColor, PromptSuggestionId.LiftShadows, PromptSuggestionId.Cool),
            client.suggest(JPEG).valueOrFail(),
        )
    }

    @Test
    fun `unknown, duplicate, conflicting and extra suggestion ids are dropped`() = runTest {
        server.enqueue(
            ids(
                "\"warm\"", "\"remove_person\"", "\"warm\"", "\"cool\"", "\"film_warm\"", "42",
                "\"brighten\"", "\"lift_shadows\"", "\"natural_color\"", "\"soften_highlights\"",
            ),
        )

        assertEquals(
            listOf(PromptSuggestionId.Warm, PromptSuggestionId.Brighten, PromptSuggestionId.NaturalColor),
            client.suggest(JPEG).valueOrFail(),
        )
    }

    @Test
    fun `an empty suggestion list is a valid answer, not a failure`() = runTest {
        server.enqueue(ids())

        assertEquals(Result.Success(emptyList<PromptSuggestionId>()), client.suggest(JPEG))
    }

    @Test
    fun `prose with no suggestion call is a failure`() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"try warm"}]}}]}"""))

        assertTrue((client.suggest(JPEG) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `a suggestion call without an id list is a failure`() = runTest {
        server.enqueue(
            parts("""{"functionCall":{"name":"suggest_directions","args":{"ids":"warm"}}}"""),
        )

        assertTrue((client.suggest(JPEG) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `an undecodable suggestion body is a failure`() = runTest {
        server.enqueue(json("not json"))

        assertTrue((client.suggest(JPEG) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `a blocked or cut short suggestion answer is a failure`() = runTest {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(Result.Failure(AppError.Invalid("blocked:SAFETY")), client.suggest(JPEG))

        server.enqueue(
            json(
                """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[""" +
                    """{"functionCall":{"name":"suggest_directions","args":{"ids":["warm"]}}}]}}]}""",
            ),
        )
        assertTrue((client.suggest(JPEG) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `suggestion status codes map like every other Gemini call`() = runTest {
        server.enqueue(error(401))
        assertEquals(Result.Failure(AppError.Unauthorized), client.suggest(JPEG))
        server.enqueue(error(429))
        assertEquals(Result.Failure(AppError.Unavailable), client.suggest(JPEG))
        server.enqueue(error(503))
        assertEquals(Result.Failure(AppError.Unavailable), client.suggest(JPEG))
    }

    @Test
    fun `a suggestion that outlasts the timeout fails`() = runTest {
        client = GeminiSuggestionClient({ config }, dispatchers, OkHttpClient(), timeoutMs = 300)
        server.enqueue(ids("\"warm\"").setBodyDelay(2, TimeUnit.SECONDS))

        assertTrue((client.suggest(JPEG) as Result.Failure).error is AppError.Io)
    }

    @Test
    fun `cancelling a suggestion mid-flight closes the call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = async(Dispatchers.IO) { client.suggest(JPEG) }
        server.takeRequest()
        job.cancel()

        assertTrue(job.isCancelled)
    }

    // ---- the catalog -----------------------------------------------------

    /** §14: the capability is a check, not a path — each must be something the planner can do. */
    @Test
    fun `every suggestion capability is something the planner offers`() {
        PromptSuggestionId.entries.forEach { id ->
            when (val capability = id.capability) {
                is SuggestionCapability.Adjust -> assertTrue(id.wire, capability.kind in plannableKinds)
                is SuggestionCapability.Style -> assertTrue(id.wire, capability.style in StyleId.entries)
            }
        }
        assertEquals(8, PromptSuggestionId.entries.size)
    }

    private fun ids(vararg values: String) =
        parts("""{"functionCall":{"name":"suggest_directions","args":{"ids":[${values.joinToString(",")}]}}}""")

    private fun parts(vararg parts: String) =
        json("""{"candidates":[{"finishReason":"STOP","content":{"parts":[${parts.joinToString(",")}]}}]}""")

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$code,"message":"m","status":""}}""")

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val API_KEY = "AIza-test-key"
        val JPEG = byteArrayOf(1, 2, 3, 4)
    }
}
