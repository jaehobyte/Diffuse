package com.diffuse.core.ai.monet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/auto_enhance.md §3, §4, §8. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class MonetClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MonetClient
    private var config = MonetConfig("")

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = MonetConfig(server.url("/").toString().trimEnd('/'), TOKEN)
        client = MonetClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- the request -----------------------------------------------------

    @Test
    fun `it posts the OpenAI-compatible chat path`() = runTest {
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(), AutoStyle.Balanced)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer $TOKEN", recorded.getHeader("Authorization"))
    }

    /** A self-hosted server may well want no credential at all. */
    @Test
    fun `a blank token sends no authorization header`() = runTest {
        config = config.copy(token = "")
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(), AutoStyle.Balanced)

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a blank address makes no request at all`() = runTest {
        config = MonetConfig("")

        val outcome = client.plan(image(), AutoStyle.Balanced)

        assertEquals(Result.Failure(AppError.Invalid("no server address")), outcome)
        assertEquals(0, server.requestCount)
    }

    /** §4: 1280 on the long edge, PNG — `inference_config.yaml`'s own `max_dimension`. */
    @Test
    fun `the photo goes on the wire as a downscaled png data url`() = runTest {
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(width = 2600, height = 1300), AutoStyle.Balanced)

        val url = sentImageUrl()
        assertTrue(url.startsWith("data:image/png;base64,"))
        val sent = decode(url)
        assertEquals(1280, sent.width)
        assertEquals(640, sent.height)
    }

    @Test
    fun `a photo already inside the limit is not resampled`() = runTest {
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(width = 640, height = 480), AutoStyle.Balanced)

        val sent = decode(sentImageUrl())
        assertEquals(640, sent.width)
    }

    @Test
    fun `the instruction carries the style's own words and the value scale`() = runTest {
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(), AutoStyle.Retro)

        val text = sentText()
        assertTrue(text.contains("scaled between -100 and +100"))
        assertTrue(text.contains("nostalgic retro vibe"))
        assertFalse("only the chosen style", text.contains("vibrant and punchy"))
    }

    /** §3: the model answers whatever it likes unless the names are on the wire. */
    @Test
    fun `the instruction lists every operation name`() = runTest {
        server.enqueue(answer(BALANCED_JSON))

        client.plan(image(), AutoStyle.Balanced)

        val text = sentText()
        MONET_OPERATIONS.keys.forEach { assertTrue("$it is missing", text.contains(it)) }
    }

    // ---- the answer ------------------------------------------------------

    @Test
    fun `the values arrive scaled into the renderer's range`() = runTest {
        server.enqueue(answer("""{"Exposure": 25, "Blacks": -40, "Contrast": 10}"""))

        val plan = (client.plan(image(), AutoStyle.Balanced) as Result.Success).value

        assertEquals(0.25f, plan.adjustments[AdjustKind.Exposure]!!, TOLERANCE)
        assertEquals(-0.4f, plan.adjustments[AdjustKind.Blacks]!!, TOLERANCE)
        assertEquals(0.1f, plan.adjustments[AdjustKind.Contrast]!!, TOLERANCE)
    }

    /** §4: "a reasoning model narrates", so the JSON is found inside the prose. */
    @Test
    fun `json wrapped in prose still parses, and the prose is the reason`() = runTest {
        server.enqueue(
            answer("""{"Exposure": 20}""", before = "The photo is underexposed and flat. "),
        )

        val plan = (client.plan(image(), AutoStyle.Balanced) as Result.Success).value

        assertEquals(0.2f, plan.adjustments[AdjustKind.Exposure]!!, TOLERANCE)
        assertEquals("The photo is underexposed and flat.", plan.reason)
    }

    @Test
    fun `every HSL name MonetGPT uses maps to a kind`() = runTest {
        val hsl = MONET_OPERATIONS.keys.filter { it.contains("Adjustment") }
        server.enqueue(answer(hsl.joinToString(", ", "{", "}") { "\"$it\": 10" }))

        val plan = (client.plan(image(), AutoStyle.Balanced) as Result.Success).value

        assertEquals(hsl.size, plan.adjustments.size)
        assertTrue(plan.adjustments.keys.all { it.hsl != null })
    }

    /** One unfamiliar name costs the user one slider, not the whole boost. */
    @Test
    fun `an unknown operation drops that entry and keeps the rest`() = runTest {
        server.enqueue(answer("""{"Dehaze": 30, "Exposure": 20}"""))

        val plan = (client.plan(image(), AutoStyle.Balanced) as Result.Success).value

        assertEquals(setOf(AdjustKind.Exposure), plan.adjustments.keys)
    }

    @Test
    fun `an answer naming no operation we know is Unsupported`() = runTest {
        server.enqueue(answer("""{"Dehaze": 30, "Texture": 10}"""))

        assertEquals(
            Result.Failure(AppError.Unsupported),
            client.plan(image(), AutoStyle.Balanced),
        )
    }

    @Test
    fun `an answer with no JSON at all is Unsupported`() = runTest {
        server.enqueue(answer(json = null, before = "This photo looks fine to me."))

        assertEquals(
            Result.Failure(AppError.Unsupported),
            client.plan(image(), AutoStyle.Balanced),
        )
    }

    @Test
    fun `a value outside the scale clamps rather than being refused`() = runTest {
        server.enqueue(answer("""{"Exposure": 400, "Contrast": -900}"""))

        val plan = (client.plan(image(), AutoStyle.Balanced) as Result.Success).value

        assertEquals(1f, plan.adjustments[AdjustKind.Exposure]!!, TOLERANCE)
        assertEquals(-1f, plan.adjustments[AdjustKind.Contrast]!!, TOLERANCE)
    }

    // ---- errors, generative_erase.md §6 row for row -----------------------

    @Test
    fun `an unauthorized answer maps to Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAUTHORIZED).setBody("nope"))

        assertEquals(
            Result.Failure(AppError.Unauthorized),
            client.plan(image(), AutoStyle.Balanced),
        )
    }

    @Test
    fun `a server error and a rate limit are both Unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_SERVER_ERROR).setBody(""))
        assertEquals(
            Result.Failure(AppError.Unavailable),
            client.plan(image(), AutoStyle.Balanced),
        )

        server.enqueue(MockResponse().setResponseCode(HTTP_TOO_MANY).setBody(""))
        assertEquals(
            Result.Failure(AppError.Unavailable),
            client.plan(image(), AutoStyle.Balanced),
        )
    }

    // ---- the probe -------------------------------------------------------

    @Test
    fun `health follows the probe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.health())
        assertEquals("/health", server.takeRequest().requestUrl?.encodedPath)

        server.enqueue(MockResponse().setResponseCode(HTTP_SERVER_ERROR))
        assertFalse(client.health())
    }

    @Test
    fun `a blank address never probes`() = runTest {
        config = MonetConfig("")

        assertFalse(client.health())
        assertEquals(0, server.requestCount)
    }

    // ---- fixtures --------------------------------------------------------

    private fun sentBody() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun sentContent() = sentBody()["messages"]!!.jsonArray[0]
        .jsonObject["content"]!!.jsonArray

    private fun sentImageUrl(): String = sentContent()[0]
        .jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content

    private fun sentText(): String = sentContent()[1].jsonObject["text"]!!.jsonPrimitive.content

    private fun decode(dataUrl: String): Bitmap {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun image(width: Int = 64, height: Int = 64): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun answer(json: String?, before: String = ""): MockResponse {
        val content = Json.encodeToString(String.serializer(), before + (json ?: ""))
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":$content}}]}""")
    }

    private companion object {
        const val TOKEN = "monet-token"
        const val TOLERANCE = 1e-4f
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_SERVER_ERROR = 500
        const val HTTP_TOO_MANY = 429
        const val BALANCED_JSON = """{"Exposure": 10}"""
    }
}
