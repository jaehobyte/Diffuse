package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.Margins
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** specs/outpaint.md §5, §8. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class GeminiOutpaintProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: GeminiSettings
    private lateinit var provider: GeminiOutpaintProvider
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
        provider = GeminiOutpaintProvider(
            client = GeminiEraseClient({ config }, dispatchers, OkHttpClient()),
            settings = settings,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the key ----------------------------------------------------------

    @Test
    fun `availability flips with the key, and a blank one makes no request`() = runTest {
        settings.update("")
        config = config.copy(apiKey = "")
        assertEquals(
            Availability.Unavailable(AppError.Invalid("no api key")),
            provider.availability.value,
        )

        val outcome = provider.outpaint(image(), MARGINS)

        assertEquals(Result.Failure(AppError.Invalid("no api key")), outcome)
        assertEquals(0, server.requestCount)

        settings.update(API_KEY)
        assertEquals(Availability.Ready, provider.availability.value)
    }

    // ---- the load-bearing one ---------------------------------------------

    /**
     * §8: the bytes on the wire are the **padded** image. Decoding the recorded body is the only
     * thing that proves `WhitePad` is in this path rather than the raw photograph being sent.
     */
    @Test
    fun `the image on the wire is padded, its border white and its interior the photograph`() =
        runTest {
            server.enqueue(imageResponse(PADDED, PADDED, fill = PAINTED))

            provider.outpaint(image(), MARGINS)

            val sent = decodeSentImage(recordedBody())
            assertEquals(PADDED, sent.width)
            assertEquals(PADDED, sent.height)
            assertNearlyWhite(sent.getPixel(1, sent.height / 2))
            assertNearlyWhite(sent.getPixel(sent.width / 2, 1))
            val middle = sent.getPixel(sent.width / 2, sent.height / 2)
            assertTrue(
                "the interior should still be the original blue",
                Color.blue(middle) - Color.red(middle) > CHANNEL_GAP,
            )
        }

    @Test
    fun `the instruction is the outpaint one, verbatim`() = runTest {
        server.enqueue(imageResponse(PADDED, PADDED, fill = PAINTED))

        provider.outpaint(image(), MARGINS)

        val text = recordedBody()["contents"]!!.jsonArray.single()
            .jsonObject["parts"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(OUTPAINT_INSTRUCTION, text)
    }

    // ---- the result -------------------------------------------------------

    @Test
    fun `the result is the whole expanded canvas, at the size that was sent`() = runTest {
        server.enqueue(imageResponse(PADDED / 2, PADDED / 2, fill = PAINTED))

        val outcome = provider.outpaint(image(), MARGINS)

        val bitmap = (outcome as Result.Success).value
        assertEquals(PADDED, bitmap.width)
        assertEquals(PADDED, bitmap.height)
    }

    /**
     * §5: the answer is mapped onto a canvas whose aspect the provider already computed, so a
     * different ratio would shift the photograph. It is refused rather than scaled.
     */
    @Test
    fun `an answer at a different aspect fails with Unsupported and commits nothing`() = runTest {
        server.enqueue(imageResponse(PADDED, PADDED / 2, fill = PAINTED))

        val outcome = provider.outpaint(image(), MARGINS)

        assertEquals(Result.Failure(AppError.Unsupported), outcome)
    }

    /** Within 2% is the model rounding its own output, not moving the frame. */
    @Test
    fun `an answer within the aspect tolerance is accepted`() = runTest {
        // 200x199 is 0.5% off square, and comes back resampled onto the canvas that was asked for.
        server.enqueue(imageResponse(NEARLY_SQUARE, NEARLY_SQUARE - 1, fill = PAINTED))

        val outcome = provider.outpaint(image(), MARGINS)

        assertEquals(PADDED, (outcome as Result.Success).value.width)
    }

    /** T51's guard, over the border rather than a mask, at the same threshold. */
    @Test
    fun `an answer whose border is still white is refused`() = runTest {
        server.enqueue(imageResponse(PADDED, PADDED, fill = Color.WHITE))

        val outcome = provider.outpaint(image(), MARGINS)

        assertEquals(Result.Failure(AppError.Unavailable), outcome)
    }

    /**
     * The photograph itself is never white here, so this proves the guard reads the **border**
     * and not the whole frame: an answer that painted the border keeps its interior white.
     */
    @Test
    fun `an answer that painted the border is kept, whatever its interior looks like`() = runTest {
        server.enqueue(borderPaintedResponse())

        val outcome = provider.outpaint(image(), MARGINS)

        assertTrue(outcome is Result.Success)
    }

    @Test
    fun `a failure from the client is passed straight through`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(HTTP_UNAUTHORIZED)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":401,"message":"bad key","status":"UNAUTHENTICATED"}}"""),
        )

        val outcome = provider.outpaint(image(), MARGINS)

        assertEquals(Result.Failure(AppError.Unauthorized), outcome)
    }

    @Test
    fun `a response carrying no image is Unsupported`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"here you go"}]}}]}"""),
        )

        assertEquals(Result.Failure(AppError.Unsupported), provider.outpaint(image(), MARGINS))
    }

    // ---- fixtures ---------------------------------------------------------

    private fun assertNearlyWhite(pixel: Int) {
        listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).forEach { channel ->
            assertTrue("expected near-white, got #${Integer.toHexString(pixel)}", channel >= 235)
        }
    }

    private fun recordedBody() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun decodeSentImage(body: JsonObject): Bitmap {
        val data = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!
            .jsonArray[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    /** Flat blue, so "not white" is a real assertion rather than a lucky default. */
    private fun image(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0, 40, 120))
        return bitmap
    }

    /** Painted border, white interior — the mirror image of the guard's failing case. */
    private fun borderPaintedResponse(): MockResponse {
        val bitmap = Bitmap.createBitmap(PADDED, PADDED, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(PAINTED)
        val interior = WhitePad.interiorOf(SIZE, SIZE, MARGINS)
        for (y in interior.top until interior.bottom) {
            for (x in interior.left until interior.right) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }
        return response(bitmap)
    }

    private fun imageResponse(width: Int, height: Int, fill: Int): MockResponse {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(fill)
        return response(bitmap)
    }

    private fun response(bitmap: Bitmap): MockResponse {
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
        const val CHANNEL_GAP = 50
        const val HTTP_UNAUTHORIZED = 401

        /** A quarter on every side: 8 + 32 + 8. */
        val MARGINS = Margins(0.25f, 0.25f, 0.25f, 0.25f)
        const val PADDED = 48
        const val NEARLY_SQUARE = 200
        val PAINTED = Color.rgb(180, 30, 30)
    }
}
