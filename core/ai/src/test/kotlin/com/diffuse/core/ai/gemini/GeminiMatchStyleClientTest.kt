package com.diffuse.core.ai.gemini

import com.diffuse.core.common.AppError
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

/** specs/style_match.md §5, §9's `MatchStyleProviderTest` list. */
@RunWith(RobolectricTestRunner::class)
class GeminiMatchStyleClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiMatchStyleClient
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
        client = GeminiMatchStyleClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    /** §5: both photographs, in the order the instruction names them, and the instruction itself. */
    @Test
    fun `the body carries both images and the instruction`() = runTest {
        server.enqueue(json(call(""""exposure":20,"contrast":30""")))

        client.match(PHOTO, REFERENCE)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        parts.forEach {
            assertEquals(
                "image/jpeg",
                it.jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
            )
        }
        assertEquals(
            MATCH_STYLE_INSTRUCTION,
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    /** §5: one function, declared with the `AdjustKind` wire names and nothing else. */
    @Test
    fun `the body declares match_style over the tone and colour kinds`() = runTest {
        server.enqueue(json(call(""""exposure":20""")))

        client.match(PHOTO, REFERENCE)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val declarations = body["tools"]!!.jsonArray[0]
            .jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(1, declarations.size)
        assertEquals(
            "match_style",
            declarations[0].jsonObject["name"]!!.jsonPrimitive.content,
        )
        val properties = declarations[0].jsonObject["parameters"]!!
            .jsonObject["properties"]!!.jsonObject.keys
        assertEquals(matchStyleKinds.map { it.wireName }.toSet(), properties)
        // §5 asks for a grade, and a grade has no vignette.
        assertTrue("vignette" !in properties)
        assertTrue("sharpen" !in properties)
    }

    /** §5: clamped per kind, then divided by 100. */
    @Test
    fun `values arrive scaled to the adjustment range`() = runTest {
        server.enqueue(json(call(""""exposure":20,"contrast":-45,"saturation":10""")))

        val adjustments = client.match(PHOTO, REFERENCE).valueOrFail()

        assertEquals(0.2f, adjustments.getValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(-0.45f, adjustments.getValue(AdjustKind.Contrast), TOLERANCE)
        assertEquals(0.1f, adjustments.getValue(AdjustKind.Saturation), TOLERANCE)
    }

    @Test
    fun `a value outside the range clamps rather than dropping the answer`() = runTest {
        server.enqueue(json(call(""""exposure":250,"contrast":-900""")))

        val adjustments = client.match(PHOTO, REFERENCE).valueOrFail()

        assertEquals(1f, adjustments.getValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(-1f, adjustments.getValue(AdjustKind.Contrast), TOLERANCE)
    }

    /** §5: a model that answers in sentences has answered wrongly, and the client drops it. */
    @Test
    fun `a prose answer is dropped`() = runTest {
        server.enqueue(json("""{"text":"The reference is warmer and more contrasty."}"""))

        val result = client.match(PHOTO, REFERENCE)

        assertTrue(result is Result.Failure)
    }

    /** An answer of all zeroes is no answer: nothing would change. */
    @Test
    fun `an answer that changes nothing is a failure, not an empty style`() = runTest {
        server.enqueue(json(call(""""exposure":0,"contrast":0""")))

        assertTrue(client.match(PHOTO, REFERENCE) is Result.Failure)
    }

    /**
     * §5: error mapping is generative_erase.md §6's, row for row and through the **same**
     * `geminiStatusError` — which is why this asserts the rows rather than a mapping of its own.
     * No new `AppError` case was added.
     */
    @Test
    fun `the status rows map the way every other Gemini call maps them`() = runTest {
        server.enqueue(error(HTTP_FORBIDDEN, "PERMISSION_DENIED"))
        assertEquals(
            AppError.Unauthorized,
            (client.match(PHOTO, REFERENCE) as Result.Failure).error,
        )

        server.enqueue(error(HTTP_UNAVAILABLE, "UNAVAILABLE"))
        assertEquals(
            AppError.Unavailable,
            (client.match(PHOTO, REFERENCE) as Result.Failure).error,
        )

        server.enqueue(error(HTTP_TOO_LARGE, "PAYLOAD_TOO_LARGE"))
        assertEquals(
            AppError.TooLarge,
            (client.match(PHOTO, REFERENCE) as Result.Failure).error,
        )
    }

    /** §5: availability is the key, with no probe. A blank one never reaches the wire. */
    @Test
    fun `no key means no request`() = runTest {
        config = GeminiConfig("", config.baseUrl)

        val result = client.match(PHOTO, REFERENCE)

        assertTrue((result as Result.Failure).error is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    private fun call(args: String) =
        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"match_style","args":{$args}}}]}}]}"""

    private fun json(part: String): MockResponse {
        val body = if (part.startsWith("{\"candidates\"")) {
            part
        } else {
            """{"candidates":[{"content":{"parts":[$part]}}]}"""
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private fun error(code: Int, status: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$code,"message":"no","status":"$status"}}""")

    private fun <T> Result<T>.valueOrFail(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val API_KEY = "AIza-test-key"
        const val TOLERANCE = 1e-6f
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_TOO_LARGE = 413
        val PHOTO = byteArrayOf(1, 2, 3, 4)
        val REFERENCE = byteArrayOf(5, 6, 7, 8)
    }
}
