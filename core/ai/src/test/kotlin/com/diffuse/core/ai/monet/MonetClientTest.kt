package com.diffuse.core.ai.monet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
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
        assertEquals(Result.Success(Unit), client.health())
        val probe = server.takeRequest()
        assertEquals("/health", probe.requestUrl?.encodedPath)
        assertEquals("Bearer $TOKEN", probe.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(HTTP_SERVER_ERROR))
        assertEquals(Result.Failure(AppError.Unavailable), client.health())
    }

    @Test
    fun `a blank address never probes`() = runTest {
        config = MonetConfig("")

        assertEquals(
            Result.Failure(AppError.Invalid(MonetClient.NO_SERVER_ADDRESS)),
            client.health(),
        )
        assertEquals(0, server.requestCount)
    }

    // ---- §6: a failed probe keeps its reason ------------------------------

    /** The token is wrong: fixable in 서버 설정, and not the same thing as an outage. */
    @Test
    fun `a rejected token on the probe is Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAUTHORIZED))

        assertEquals(Result.Failure(AppError.Unauthorized), client.health())
    }

    /** MonetGPT answers 503 while the checkpoint is still loading. */
    @Test
    fun `a server still loading its model is Unavailable, not unreachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_UNAVAILABLE))

        assertEquals(Result.Failure(AppError.Unavailable), client.health())
    }

    @Test
    fun `something answering 404 at health is the wrong address`() = runTest {
        server.enqueue(MockResponse().setResponseCode(HTTP_NOT_FOUND))

        assertEquals(
            Result.Failure(AppError.Invalid(MonetClient.NOT_A_MONET_SERVER)),
            client.health(),
        )
    }

    @Test
    fun `a refused connection is Io`() = runTest {
        val gone = MockWebServer().apply { start() }
        val closed = gone.url("/").toString().trimEnd('/')
        gone.shutdown()
        config = MonetConfig(closed, TOKEN)

        val outcome = client.health()

        assertTrue((outcome as Result.Failure).error is AppError.Io)
    }

    /** §4: the probe has its own short timeout; the 120 s read is the generation's alone. */
    @Test
    fun `a probe that never answers times out in seconds`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val started = System.nanoTime()
        val outcome = client.health()
        val elapsedS = (System.nanoTime() - started) / NANOS_PER_SECOND

        assertTrue((outcome as Result.Failure).error is AppError.Io)
        assertTrue("took $elapsedS s", elapsedS < PROBE_CEILING_S)
    }

    /** A typo saved before validation existed must not crash the request builder. */
    @Test
    fun `a malformed address is Invalid on both calls and never crashes`() = runTest {
        config = MonetConfig("htp:/ not a url", TOKEN)

        val invalid = Result.Failure(AppError.Invalid(MonetClient.INVALID_SERVER_ADDRESS))
        assertEquals(invalid, client.health())
        assertEquals(invalid, client.plan(image(), AutoStyle.Balanced))
    }

    /** §6: cancelling a generation reaches the socket, not just the coroutine. */
    @Test
    fun `cancelling a plan cancels the HTTP call`() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        client = MonetClient(
            { config },
            dispatchers,
            OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        cancelled.complete(Unit)
                    }
                })
                .build(),
        )
        server.enqueue(answer(BALANCED_JSON).setHeadersDelay(SLOW_S, TimeUnit.SECONDS))

        val job = launch(Dispatchers.IO) { client.plan(image(), AutoStyle.Balanced) }
        server.takeRequest()
        job.cancelAndJoin()

        withTimeout(CANCEL_WAIT_MS) { cancelled.await() }
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
        const val HTTP_UNAVAILABLE = 503
        const val HTTP_NOT_FOUND = 404
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val PROBE_CEILING_S = 9L
        const val SLOW_S = 2L
        const val CANCEL_WAIT_MS = 5_000L
        const val BALANCED_JSON = """{"Exposure": 10}"""
    }
}
