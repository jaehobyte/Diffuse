package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
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

/** specs/generative_erase.md §5, §6. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class GeminiEraseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiEraseClient
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
        client = GeminiEraseClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the request -----------------------------------------------------

    @Test
    fun `it posts to the generateContent path`() = runTest {
        server.enqueue(imageResponse())

        client.erase(JPEG, hint = null)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/v1beta/models/gemini-2.5-flash-image:generateContent",
            recorded.requestUrl?.encodedPath,
        )
    }

    @Test
    fun `the key travels in the header and never in the URL`() = runTest {
        server.enqueue(imageResponse())

        client.erase(JPEG, hint = null)

        val recorded = server.takeRequest()
        assertEquals(API_KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.requestUrl?.queryParameter("key"))
        assertFalse(recorded.requestUrl.toString().contains(API_KEY))
        assertFalse(recorded.path.orEmpty().contains(API_KEY))
    }

    @Test
    fun `the body carries the image, the instruction and the IMAGE modality`() = runTest {
        server.enqueue(imageResponse())

        client.erase(JPEG, hint = null)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertArrayEquals(JPEG, Base64.decode(inline["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
        assertEquals(GeminiEraseClient.INSTRUCTION, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("IMAGE"),
            body["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `an unused part field is absent rather than null`() = runTest {
        server.enqueue(imageResponse())

        client.erase(JPEG, hint = null)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertFalse("text" in parts[0].jsonObject)
        assertFalse("inlineData" in parts[1].jsonObject)
    }

    @Test
    fun `a hint appends one sentence, and a blank one appends nothing`() {
        assertEquals(
            GeminiEraseClient.INSTRUCTION +
                " The painted area used to contain a car, which has been removed on purpose: " +
                "reconstruct what was behind it and do not draw it again.",
            GeminiEraseClient.instruction("a car"),
        )
        assertEquals(GeminiEraseClient.INSTRUCTION, GeminiEraseClient.instruction("  "))
        assertEquals(GeminiEraseClient.INSTRUCTION, GeminiEraseClient.instruction(null))
    }

    // ---- the response ----------------------------------------------------

    /** T51: the two sentences that close the "it came back white" failure the device showed. */
    @Test
    fun `the instruction forbids leaving white and forbids echoing the input`() {
        assertTrue(
            GeminiEraseClient.INSTRUCTION.contains("no white or near-white patch may remain"),
        )
        assertTrue(
            GeminiEraseClient.INSTRUCTION
                .contains("returning the input image unchanged is not an acceptable answer"),
        )
        assertTrue(GeminiEraseClient.INSTRUCTION.contains("do not draw any new object"))
    }

    @Test
    fun `the hint says the thing was removed, never what to draw`() {
        val instruction = GeminiEraseClient.instruction("a bus")

        assertTrue(instruction.contains("has been removed on purpose"))
        assertTrue(instruction.contains("do not draw it again"))
    }

    @Test
    fun `a base64 image part decodes`() = runTest {
        server.enqueue(imageResponse())

        val outcome = client.erase(JPEG, hint = null)

        assertEquals(GeminiEraseClient.Outcome.Success(RESULT), outcome)
    }

    @Test
    fun `a text part is skipped to reach the image part`() = runTest {
        server.enqueue(
            json(
                """
                {"candidates":[{"content":{"parts":[
                  {"text":"Sure, here you go."},
                  {"inlineData":{"mimeType":"image/png","data":"${base64(RESULT)}"}}
                ]}}]}
                """.trimIndent(),
            ),
        )

        val outcome = client.erase(JPEG, hint = null)

        assertEquals(GeminiEraseClient.Outcome.Success(RESULT), outcome)
    }

    @Test
    fun `a 200 with no image part is Unsupported`() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"I cannot."}]}}]}"""))

        assertEquals(failure(AppError.Unsupported), client.erase(JPEG, hint = null))
    }

    @Test
    fun `a blocked prompt is Invalid with the blocked prefix`() = runTest {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))

        assertEquals(
            failure(AppError.Invalid("blocked:SAFETY")),
            client.erase(JPEG, hint = null),
        )
    }

    @Test
    fun `a candidate cut short for safety is blocked too`() = runTest {
        server.enqueue(json("""{"candidates":[{"finishReason":"IMAGE_SAFETY"}]}"""))

        assertEquals(
            failure(AppError.Invalid("blocked:IMAGE_SAFETY")),
            client.erase(JPEG, hint = null),
        )
    }

    @Test
    fun `an ordinary finishReason is not a block`() = runTest {
        server.enqueue(
            json(
                """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"inlineData":{"mimeType":"image/png","data":"${base64(RESULT)}"}}
                ]}}]}
                """.trimIndent(),
            ),
        )

        assertEquals(GeminiEraseClient.Outcome.Success(RESULT), client.erase(JPEG, hint = null))
    }

    // ---- §6, row by row --------------------------------------------------

    @Test
    fun `400 INVALID_ARGUMENT is Invalid carrying the message`() = runTest {
        server.enqueue(error(400, "INVALID_ARGUMENT", "bad image"))

        assertEquals(failure(AppError.Invalid("bad image")), client.erase(JPEG, hint = null))
    }

    @Test
    fun `400 FAILED_PRECONDITION is Unavailable`() = runTest {
        server.enqueue(error(400, "FAILED_PRECONDITION", "billing not enabled"))

        assertEquals(failure(AppError.Unavailable), client.erase(JPEG, hint = null))
    }

    @Test
    fun `401 and 403 are Unauthorized`() = runTest {
        server.enqueue(error(401, "UNAUTHENTICATED", "no key"))
        server.enqueue(error(403, "PERMISSION_DENIED", "denied"))

        assertEquals(failure(AppError.Unauthorized), client.erase(JPEG, hint = null))
        assertEquals(failure(AppError.Unauthorized), client.erase(JPEG, hint = null))
    }

    @Test
    fun `404 is Unsupported`() = runTest {
        server.enqueue(error(404, "NOT_FOUND", "model retired"))

        assertEquals(failure(AppError.Unsupported), client.erase(JPEG, hint = null))
    }

    @Test
    fun `413 is TooLarge`() = runTest {
        server.enqueue(error(413, "", "too big"))

        assertEquals(failure(AppError.TooLarge), client.erase(JPEG, hint = null))
    }

    @Test
    fun `429 is Unavailable rather than a retry`() = runTest {
        server.enqueue(error(429, "RESOURCE_EXHAUSTED", "quota"))

        assertEquals(failure(AppError.Unavailable), client.erase(JPEG, hint = null))
    }

    @Test
    fun `500, 503 and 504 are Unavailable`() = runTest {
        listOf(500 to "INTERNAL", 503 to "UNAVAILABLE", 504 to "DEADLINE_EXCEEDED")
            .forEach { (code, status) ->
                server.enqueue(error(code, status, status))
                assertEquals(failure(AppError.Unavailable), client.erase(JPEG, hint = null))
            }
    }

    @Test
    fun `an unmapped status is Io`() = runTest {
        server.enqueue(error(418, "TEAPOT", "short and stout"))

        val outcome = client.erase(JPEG, hint = null)

        assertTrue((outcome as GeminiEraseClient.Outcome.Failure).error is AppError.Io)
    }

    @Test
    fun `a transport failure is Io`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val outcome = client.erase(JPEG, hint = null)

        assertTrue((outcome as GeminiEraseClient.Outcome.Failure).error is AppError.Io)
    }

    @Test
    fun `an undecodable body is Io`() = runTest {
        server.enqueue(json("not json at all"))

        val outcome = client.erase(JPEG, hint = null)

        assertTrue((outcome as GeminiEraseClient.Outcome.Failure).error is AppError.Io)
    }

    @Test
    fun `a blank key never reaches the wire`() = runTest {
        config = GeminiConfig("", server.url("/").toString().trimEnd('/'))

        assertEquals(failure(AppError.Invalid("no api key")), client.erase(JPEG, hint = null))
        assertEquals(0, server.requestCount)
    }

    // ---- cancellation ----------------------------------------------------

    @Test
    fun `cancelling mid-flight closes the call`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = async(Dispatchers.IO) { client.erase(JPEG, hint = null) }
        server.takeRequest()
        job.cancel()

        assertTrue(job.isCancelled)
    }

    // ---- fixtures --------------------------------------------------------

    private fun failure(error: AppError) = GeminiEraseClient.Outcome.Failure(error)

    private fun imageResponse() = json(
        """
        {"candidates":[{"content":{"parts":[
          {"inlineData":{"mimeType":"image/png","data":"${base64(RESULT)}"}}
        ]}}]}
        """.trimIndent(),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun error(code: Int, status: String, message: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$code,"message":"$message","status":"$status"}}""")

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertTrue(expected.contentEquals(actual))

    private companion object {
        const val API_KEY = "AIza-secret-key"
        val JPEG = byteArrayOf(1, 2, 3, 4, 5)
        val RESULT = byteArrayOf(9, 8, 7, 6)
    }
}
