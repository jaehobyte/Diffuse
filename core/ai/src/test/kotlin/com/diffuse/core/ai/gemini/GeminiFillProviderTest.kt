package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
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
import java.io.ByteArrayOutputStream

/** specs/generative_fill.md §4, §9. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class GeminiFillProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: GeminiSettings
    private lateinit var provider: GeminiFillProvider
    private var config = GeminiConfig(API_KEY)

    /** `default` is unconfined so `availability`'s `stateIn` settles inside the test. */
    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = GeminiConfig(API_KEY, server.url("/").toString().trimEnd('/'))
        settings = GeminiSettings(ApplicationProvider.getApplicationContext())
        settings.update(API_KEY)
        provider = GeminiFillProvider(
            client = GeminiEraseClient({ config }, dispatchers, OkHttpClient()),
            settings = settings,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the guards -------------------------------------------------------

    @Test
    fun `a blank prompt fails before any request is built`() = runTest {
        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), prompt = "   ")

        assertEquals(Result.Failure(AppError.Invalid("empty prompt")), outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a mask of the wrong size fails before any request is built`() = runTest {
        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE + 1, SIZE), PROMPT)

        assertEquals(
            Result.Failure(AppError.Invalid("mask must be the image's size")),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank key is Unavailable, and no request is ever made`() = runTest {
        settings.update("")
        config = config.copy(apiKey = "")

        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        assertEquals(Result.Failure(AppError.Invalid("no api key")), outcome)
        assertEquals(
            Availability.Unavailable(AppError.Invalid("no api key")),
            provider.availability.value,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `availability flips as soon as a key is typed`() = runTest {
        settings.update("")
        assertEquals(
            Availability.Unavailable(AppError.Invalid("no api key")),
            provider.availability.value,
        )

        settings.update(API_KEY)

        assertEquals(Availability.Ready, provider.availability.value)
    }

    // ---- the load-bearing one ---------------------------------------------

    /**
     * §9: the bytes on the wire are the *whitened* image. Decoding the recorded body is the only
     * thing that proves `WhiteFill` is in this path too, rather than only the eraser's.
     */
    @Test
    fun `the image on the wire is white where the mask was, and untouched elsewhere`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE, fill = FILLED))

        provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        val body = recordedBody()
        val sent = decodeSentImage(body)
        assertEquals(SIZE, sent.width)
        assertNearlyWhite(sent.getPixel(2, 2))
        assertTrue(
            "the unmasked half should still be the original blue",
            Color.blue(sent.getPixel(SIZE - 2, 2)) - Color.red(sent.getPixel(SIZE - 2, 2)) > 50,
        )
    }

    @Test
    fun `the prompt reaches the wire inside the instruction, verbatim`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE, fill = FILLED))

        provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        val text = recordedBody()["contents"]!!.jsonArray.single()
            .jsonObject["parts"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(fillInstruction(PROMPT), text)
        assertTrue(text.contains(PROMPT))
    }

    // ---- the result -------------------------------------------------------

    @Test
    fun `the result comes back at the caller's size, whatever the model returned`() = runTest {
        server.enqueue(imageResponse(SIZE / 2, SIZE / 2, fill = FILLED))

        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        val bitmap = (outcome as Result.Success).value
        assertEquals(SIZE, bitmap.width)
        assertEquals(SIZE, bitmap.height)
    }

    /** T51's guard, shared: an answer that is still a hole is a retry, not a committed patch. */
    @Test
    fun `a result that is still white where the mask was is refused`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE, fill = Color.WHITE))

        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        assertEquals(Result.Failure(AppError.Unavailable), outcome)
    }

    @Test
    fun `a result that was actually filled is kept`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE, fill = FILLED))

        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        assertTrue(outcome is Result.Success)
    }

    @Test
    fun `a failure from the client is passed straight through`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_UNAUTHORIZED)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":401,"message":"bad key","status":"UNAUTHENTICATED"}}"""),
        )

        val outcome = provider.fill(image(SIZE, SIZE), mask(SIZE, SIZE), PROMPT)

        assertEquals(Result.Failure(AppError.Unauthorized), outcome)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun assertNearlyWhite(pixel: Int) {
        listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).forEach { channel ->
            assertTrue("expected near-white, got #${Integer.toHexString(pixel)}", channel >= 235)
        }
    }

    private fun recordedBody() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun decodeSentImage(body: kotlinx.serialization.json.JsonObject): Bitmap {
        val data = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!
            .jsonArray[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    /** Flat blue, so "not white" is a real assertion rather than a lucky default. */
    private fun image(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0, 40, 120))
        return bitmap
    }

    /** Covers the left half. */
    private fun mask(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (x < width / 2) OPAQUE_ALPHA else 0)
            }
        }
        return bitmap
    }

    private fun imageResponse(width: Int, height: Int, fill: Int): MockResponse {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(fill)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {"candidates":[{"content":{"parts":[
                  {"inlineData":{"mimeType":"image/png","data":"${
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }"}}
                ]}}]}
                """.trimIndent(),
            )
    }

    private companion object {
        const val API_KEY = "AIza-secret-key"
        const val SIZE = 32
        const val OPAQUE_ALPHA = 0xFF shl 24
        const val PROMPT = "빨간 우산"
        const val HTTP_UNAUTHORIZED = 401
        val FILLED = Color.rgb(180, 30, 30)
    }
}
