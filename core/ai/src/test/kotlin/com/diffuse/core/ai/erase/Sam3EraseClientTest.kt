package com.diffuse.core.ai.erase

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.sam3.Sam3Client
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ai.sam3.Sam3SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** specs/generative_erase.md §8. MockWebServer binds localhost only. */
@RunWith(RobolectricTestRunner::class)
class Sam3EraseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: Sam3EraseProvider
    private var config = Sam3Config("", TOKEN)

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = Sam3Config(server.url("/").toString().trimEnd('/'), TOKEN)
        val okHttp = OkHttpClient()
        provider = Sam3EraseProvider(
            client = Sam3EraseClient({ config }, dispatchers, okHttp),
            segmentation = Sam3SegmentationProvider(Sam3Client({ config }, dispatchers, okHttp)) {
                config
            },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `erase posts the image, the mask and the hint`() = runTest {
        server.enqueue(pngResponse())

        val out = provider.erase(image(), mask(), hint = "사람").success()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/edit/erase", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("""name="image""""))
        assertTrue(body.contains("""name="mask""""))
        assertTrue(body.contains("""name="hint""""))
        assertTrue(body.contains("사람"))
        assertEquals(SIZE, out.width)
        assertEquals(SIZE, out.height)
    }

    @Test
    fun `a null hint is simply omitted`() = runTest {
        server.enqueue(pngResponse())

        provider.erase(image(), mask(), hint = null).success()

        assertTrue(!server.takeRequest().body.readUtf8().contains("""name="hint""""))
    }

    @Test
    fun `the result is scaled back to the caller's size`() = runTest {
        server.enqueue(pngResponse(size = SIZE / 2))

        val out = provider.erase(image(), mask(), hint = null).success()

        assertEquals(SIZE, out.width)
        assertEquals(Bitmap.Config.ARGB_8888, out.config)
    }

    @Test
    fun `a mask of the wrong size never reaches the network`() = runTest {
        val wrong = Bitmap.createBitmap(SIZE / 2, SIZE / 2, Bitmap.Config.ALPHA_8)

        assertTrue(provider.erase(image(), wrong, null).failure() is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `503 becomes Unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"not_ready"}"""))

        assertEquals(AppError.Unavailable, provider.erase(image(), mask(), null).failure())
    }

    @Test
    fun `401 becomes Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        assertEquals(AppError.Unauthorized, provider.erase(image(), mask(), null).failure())
    }

    @Test
    fun `400 carries the server detail`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"invalid_prompt","detail":"mask is empty"}"""),
        )

        assertEquals(
            AppError.Invalid("mask is empty"),
            provider.erase(image(), mask(), null).failure(),
        )
    }

    @Test
    fun `a dropped connection is an Io failure, not a crash`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(provider.erase(image(), mask(), null).failure() is AppError.Io)
    }

    @Test
    fun `an unconfigured base URL never reaches the network`() = runTest {
        config = config.copy(baseUrl = "")

        assertTrue(provider.erase(image(), mask(), null).failure() is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `availability follows the segmentation provider`() {
        assertEquals(Availability.Ready, provider.availability.value)
    }

    // ---- helpers ---------------------------------------------------------

    private fun image(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return bitmap
    }

    private fun mask(): Bitmap =
        MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, SIZE / 4f)

    private fun pngResponse(size: Int = SIZE): MockResponse {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(out.toByteArray()))
    }

    private fun <T> Result<T>.success(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private fun <T> Result<T>.failure(): AppError = when (this) {
        is Result.Failure -> error
        is Result.Success -> throw AssertionError("expected failure, got $value")
    }

    private companion object {
        const val TOKEN = "test-token"
        const val SIZE = 64
    }
}
