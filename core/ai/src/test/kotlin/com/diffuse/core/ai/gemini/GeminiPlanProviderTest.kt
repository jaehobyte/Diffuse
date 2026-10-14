package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
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

/** specs/vibe_edit.md §8, §12. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class GeminiPlanProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: GeminiSettings
    private lateinit var provider: GeminiPlanProvider
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
        provider = GeminiPlanProvider(
            client = GeminiPlanClient({ config }, dispatchers, OkHttpClient()),
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
    fun `a blank 지시 fails before anything is encoded`() = runTest {
        assertEquals(
            Result.Failure(AppError.Invalid("empty request")),
            provider.plan(image(SIZE, SIZE), PlanRequest("   ", PlanMode.Direct)),
        )
        assertEquals(0, server.requestCount)
    }

    /** §14: 보정 추천 needs no words — the photo alone is a valid question. */
    @Test
    fun `a blank recommendation is sent`() = runTest {
        server.enqueue(planResponse(source = "default"))

        val result = provider.plan(image(SIZE, SIZE), PlanRequest("", PlanMode.Recommend))

        assertTrue(result is Result.Success)
        assertEquals(1, server.requestCount)
    }

    // ---- the encoder, reused unchanged ------------------------------------

    @Test
    fun `the image on the wire is at most 1024 on the long edge`() = runTest {
        server.enqueue(planResponse())

        provider.plan(image(2000, 1000), REQUEST)

        val sent = decodeSentImage()
        assertEquals(GeminiImageCodec.MAX_LONG_EDGE, sent.width)
        assertEquals(GeminiImageCodec.MAX_LONG_EDGE / 2, sent.height)
    }

    @Test
    fun `a small image is sent at its own size`() = runTest {
        server.enqueue(planResponse())

        provider.plan(image(SIZE, SIZE), REQUEST)

        assertEquals(SIZE, decodeSentImage().width)
    }

    // ---- the answer -------------------------------------------------------

    @Test
    fun `the sentence reaches the model and the steps come back`() = runTest {
        server.enqueue(planResponse())

        val proposal = provider.plan(image(SIZE, SIZE), REQUEST)

        assertEquals(
            listOf(
                PlanStep.Select("tree", "n1"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")),
            ),
            (proposal as Result.Success).value.plan.steps,
        )
        assertEquals(REQUEST.text, sentText())
    }

    /** §14: prose is nothing the app can run, and it is refused rather than read as "nothing". */
    @Test
    fun `zero function calls is refused, not an empty plan`() = runTest {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"I cannot."}]}}]}"""))

        val error = (provider.plan(image(SIZE, SIZE), REQUEST) as Result.Failure).error
        assertTrue((error as AppError.Invalid).detail.startsWith(GeminiPlanClient.UNPLANNABLE_PREFIX))
    }

    @Test
    fun `a failure from the wire propagates`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":401,"message":"no key","status":"UNAUTHENTICATED"}}"""),
        )

        assertEquals(
            Result.Failure(AppError.Unauthorized),
            provider.plan(image(SIZE, SIZE), REQUEST),
        )
    }

    // ---- fixtures ---------------------------------------------------------

    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun sentParts(body: kotlinx.serialization.json.JsonObject) =
        body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray

    private fun decodeSentImage(): Bitmap {
        val data = sentParts(sentBody())[0].jsonObject["inlineData"]!!
            .jsonObject["data"]!!.jsonPrimitive.content
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun sentText(): String =
        sentParts(sentBody())[2].jsonObject["text"]!!.jsonPrimitive.content

    private fun planResponse(source: String = "user") = json(
        """
        {"candidates":[{"content":{"parts":[
          {"functionCall":{"name":"propose_edit","args":{"outcome":"ready",
            "direction":{"subject":"나무","objective":"나무를 더 푸르게","success":"나무만 더 푸르다","source":"$source"},
            "goals":[{"id":"g1","text":"나무를 더 푸르게","source":"$source","scope":"subject"}],
            "workflow":[
              {"tool":"select_region","goal_id":"g1","args":{"phrase":"tree","target":"n1"}},
              {"tool":"adjust","goal_id":"g1","args":{"kind":"saturation","value":0.3,"target":"n1"}}
            ]}}}
        ]}}]}
        """.trimIndent(),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun image(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 80, 200))
        }

    private companion object {
        const val API_KEY = "AIza-test-key"
        val REQUEST = PlanRequest("나무를 좀 더 푸르게 해줘", PlanMode.Direct)
        const val SIZE = 64
    }
}
