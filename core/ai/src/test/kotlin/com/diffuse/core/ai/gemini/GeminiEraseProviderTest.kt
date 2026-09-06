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

/** specs/generative_erase.md §7, §12. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class GeminiEraseProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: GeminiSettings
    private lateinit var provider: GeminiEraseProvider
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
        provider = GeminiEraseProvider(
            client = GeminiEraseClient({ config }, dispatchers, OkHttpClient()),
            settings = settings,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- availability, with no probe --------------------------------------

    @Test
    fun `a blank key is Unavailable, and no request is ever made`() = runTest {
        settings.update("")

        assertEquals(
            Availability.Unavailable(AppError.Invalid("no api key")),
            provider.availability.value,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `availability flips as soon as a key is typed`() = runTest {
        settings.update("")
        assertTrue(provider.availability.value is Availability.Unavailable)

        settings.update("AIza-typed")

        assertEquals(Availability.Ready, provider.availability.value)
        assertEquals(0, server.requestCount)
    }

    // ---- the guard --------------------------------------------------------

    @Test
    fun `a mask of the wrong size fails before any request is built`() = runTest {
        val outcome = provider.erase(image(SIZE, SIZE), mask(SIZE + 1, SIZE), hint = null)

        assertEquals(
            Result.Failure(AppError.Invalid("mask must be the image's size")),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    // ---- the load-bearing one ---------------------------------------------

    /**
     * §12: the bytes on the wire must be the *whitened* image, not the original. Decoding the
     * recorded body is the only thing that proves `WhiteFill` is actually in the path.
     */
    @Test
    fun `the image on the wire is white where the mask was, and untouched elsewhere`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE))

        provider.erase(image(SIZE, SIZE), mask(SIZE, SIZE), hint = null)

        val sent = decodeSentImage()
        assertEquals(SIZE, sent.width)
        // The mask covers the left half. JPEG is lossy, so this is "white" to within a
        // quantization step rather than exactly 0xFFFFFFFF — the boundary column especially.
        assertNearlyWhite(sent.getPixel(2, 2))
        assertNearlyWhite(sent.getPixel(HALF / 2, SIZE - 2))
        assertTrue(
            "the unmasked half should still be the original blue",
            Color.blue(sent.getPixel(SIZE - 2, 2)) - Color.red(sent.getPixel(SIZE - 2, 2)) > 50,
        )
    }

    @Test
    fun `the sent image declares image jpeg`() = runTest {
        server.enqueue(imageResponse(SIZE, SIZE))

        provider.erase(image(SIZE, SIZE), mask(SIZE, SIZE), hint = null)

        assertEquals("image/jpeg", inlineData()["mimeType"]!!.jsonPrimitive.content)
    }

    // ---- the result -------------------------------------------------------

    @Test
    fun `the result comes back at the caller's size, whatever the model returned`() = runTest {
        server.enqueue(imageResponse(64, 64))

        val outcome = provider.erase(image(SIZE, SIZE), mask(SIZE, SIZE), hint = null)

        val bitmap = (outcome as Result.Success).value
        assertEquals(SIZE, bitmap.width)
        assertEquals(SIZE, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test
    fun `a failure from the client is passed straight through`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED"}}"""),
        )

        val outcome = provider.erase(image(SIZE, SIZE), mask(SIZE, SIZE), hint = null)

        assertEquals(Result.Failure(AppError.Unavailable), outcome)
    }

    @Test
    fun `bytes that do not decode are Unsupported`() = runTest {
        server.enqueue(
            json(
                """
                {"candidates":[{"content":{"parts":[
                  {"inlineData":{"mimeType":"image/png","data":"${base64(byteArrayOf(1, 2, 3))}"}}
                ]}}]}
                """.trimIndent(),
            ),
        )

        val outcome = provider.erase(image(SIZE, SIZE), mask(SIZE, SIZE), hint = null)

        assertEquals(Result.Failure(AppError.Unsupported), outcome)
    }

    @Test
    fun `an image larger than the cap is downscaled before it is sent`() = runTest {
        server.enqueue(imageResponse(1024, 1024))

        provider.erase(image(2048, 2048), mask(2048, 2048), hint = null)

        val sent = decodeSentImage()
        assertEquals(GeminiImageCodec.MAX_LONG_EDGE, sent.width)
        assertNearlyWhite(sent.getPixel(2, 2))
    }

    // ---- fixtures ---------------------------------------------------------

    private fun assertNearlyWhite(pixel: Int) {
        listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).forEach { channel ->
            assertTrue("expected near-white, got #${Integer.toHexString(pixel)}", channel >= 235)
        }
    }

    private fun decodeSentImage(): Bitmap {
        val data = inlineData()["data"]!!.jsonPrimitive.content
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun inlineData() = Json
        .parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["contents"]!!
        .jsonArray.single().jsonObject["parts"]!!
        .jsonArray[0].jsonObject["inlineData"]!!.jsonObject

    /** A gradient, so "not white" is a real assertion rather than a lucky default. */
    private fun image(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(0, 40, 120))
            }
        }
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

    private fun imageResponse(width: Int, height: Int): MockResponse {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        return json(
            """
            {"candidates":[{"content":{"parts":[
              {"inlineData":{"mimeType":"image/png","data":"${base64(out.toByteArray())}"}}
            ]}}]}
            """.trimIndent(),
        )
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val API_KEY = "AIza-secret-key"
        const val SIZE = 32
        const val HALF = 16
        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}
