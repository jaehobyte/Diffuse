package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.diffuse.core.ai.PromptSuggestionId
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** specs/vibe_edit.md §14: the encoder path `GeminiPlanProvider` uses, reused unchanged. */
@RunWith(RobolectricTestRunner::class)
class GeminiSuggestionProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiSuggestionProvider

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = GeminiConfig(API_KEY, server.url("/").toString().trimEnd('/'))
        provider = GeminiSuggestionProvider(
            client = GeminiSuggestionClient({ config }, dispatchers, OkHttpClient()),
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the suggestion image is downscaled to 1024 and the shared preview survives`() = runTest {
        server.enqueue(answer())
        val preview = image(2000, 1000)

        val result = provider.suggest(preview)

        assertEquals(Result.Success(listOf(PromptSuggestionId.Cool)), result)
        assertFalse("the caller's preview must not be recycled", preview.isRecycled)
        val data = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["inlineData"]!!
            .jsonObject["data"]!!.jsonPrimitive.content
        val bytes = Base64.decode(data, Base64.DEFAULT)
        val sent = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(GeminiImageCodec.MAX_LONG_EDGE, sent.width)
        assertEquals(GeminiImageCodec.MAX_LONG_EDGE / 2, sent.height)
    }

    private fun answer() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[""" +
                """{"functionCall":{"name":"suggest_directions","args":{"ids":["cool"]}}}]}}]}""",
        )

    private fun image(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    private companion object {
        const val API_KEY = "AIza-test-key"
    }
}
