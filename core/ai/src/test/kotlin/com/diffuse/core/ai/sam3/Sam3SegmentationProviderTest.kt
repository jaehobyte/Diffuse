package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Base64
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.ai.SegSession
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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

/** specs/segmentation.md §5, §7, §8, §9. */
@RunWith(RobolectricTestRunner::class)
class Sam3SegmentationProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: Sam3SegmentationProvider
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
        provider = Sam3SegmentationProvider(Sam3Client({ config }, dispatchers)) { config }
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- sessions --------------------------------------------------------

    @Test
    fun `open reports the caller's image size, not the uploaded size`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))

        val session = provider.open(image(WORKING_SIZE)).success()

        assertEquals(WORKING_SIZE, session.imageWidth)
        assertEquals(WORKING_SIZE, session.imageHeight)
        assertEquals("/v1/images", server.takeRequest().path)
    }

    @Test
    fun `opening a second session releases the first`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(uploadResponse("two", UPLOAD_SIZE, UPLOAD_SIZE))

        provider.open(image(WORKING_SIZE)).success()
        provider.open(image(WORKING_SIZE)).success()

        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/images/one", delete.path)
    }

    @Test
    fun `close releases the session`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(MockResponse().setResponseCode(204))
        val session = provider.open(image(WORKING_SIZE)).success()
        server.takeRequest()

        provider.close(session)

        assertEquals("/v1/images/one", server.takeRequest().path)
    }

    // ---- masks -----------------------------------------------------------

    @Test
    fun `masks are scaled from the uploaded size to the working size`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(masksResponse())
        val session = provider.open(image(WORKING_SIZE)).success()

        val mask = provider.byPoints(session, fg()).success()

        assertEquals(WORKING_SIZE, mask.alpha.width)
        assertEquals(WORKING_SIZE, mask.alpha.height)
        assertEquals(Bitmap.Config.ALPHA_8, mask.alpha.config)
    }

    @Test
    fun `byText returns every instance, in the server's order`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(masksResponse(scores = listOf(0.9f, 0.7f)))
        val session = provider.open(image(WORKING_SIZE)).success()

        val masks = provider.byText(session, "사람").success()

        assertEquals(listOf(0.9f, 0.7f), masks.map { it.score })
    }

    @Test
    fun `a blank phrase never reaches the network`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        val session = provider.open(image(WORKING_SIZE)).success()
        val before = server.requestCount

        assertTrue(provider.byText(session, "  ").failure() is AppError.Invalid)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `a prompt for a session that is not open is rejected`() = runTest {
        val stale = SegSession("gone", WORKING_SIZE, WORKING_SIZE, Long.MAX_VALUE)

        assertTrue(provider.byPoints(stale, fg()).failure() is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    // ---- session expiry (specs/segmentation.md 5) ------------------------

    @Test
    fun `an expired session is re-uploaded once and the prompt replayed`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(expired())
        server.enqueue(uploadResponse("two", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(masksResponse())
        val session = provider.open(image(WORKING_SIZE)).success()

        val mask = provider.byPoints(session, fg()).success()

        assertEquals(WORKING_SIZE, mask.alpha.width)
        assertEquals(listOf("/v1/images", "/v1/images/one/segment/points", "/v1/images"), paths(3))
        assertEquals("/v1/images/two/segment/points", server.takeRequest().path)
    }

    @Test
    fun `expiring twice in a row is Unavailable, not a retry loop`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(expired())
        server.enqueue(uploadResponse("two", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(expired())
        val session = provider.open(image(WORKING_SIZE)).success()

        assertEquals(AppError.Unavailable, provider.byPoints(session, fg()).failure())
        assertEquals(4, server.requestCount)
    }

    // ---- availability (specs/segmentation.md 7) --------------------------

    @Test
    fun `an unconfigured base URL is Unavailable and never reaches the network`() = runTest {
        config = config.copy(baseUrl = "")
        val unconfigured = Sam3SegmentationProvider(Sam3Client({ config }, dispatchers)) { config }

        unconfigured.refresh()

        val availability = unconfigured.availability.value
        assertTrue(availability is Availability.Unavailable && availability.reason is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh probes healthz`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        provider.refresh()

        assertEquals(Availability.Ready, provider.availability.value)
        assertEquals("/healthz", server.takeRequest().path)
    }

    @Test
    fun `a failing healthz makes the provider unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"loading"}"""))

        provider.refresh()

        assertEquals(
            Availability.Unavailable(AppError.Unavailable),
            provider.availability.value,
        )
    }

    @Test
    fun `a rejected token makes the provider unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        provider.open(image(WORKING_SIZE)).failure()

        assertEquals(
            Availability.Unavailable(AppError.Unauthorized),
            provider.availability.value,
        )
    }

    @Test
    fun `a rejected prompt does not make the provider unavailable`() = runTest {
        server.enqueue(uploadResponse("one", UPLOAD_SIZE, UPLOAD_SIZE))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_prompt"}"""))
        val session = provider.open(image(WORKING_SIZE)).success()

        provider.byPoints(session, fg()).failure()

        assertEquals(Availability.Ready, provider.availability.value)
    }

    // ---- helpers ---------------------------------------------------------

    private fun paths(count: Int) = List(count) { server.takeRequest().path }

    private fun fg() = PointPrompt(listOf(PointF(0.5f, 0.5f)), listOf(true))

    private fun image(size: Int) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun uploadResponse(id: String, width: Int, height: Int) =
        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
            """{"image_id":"$id","width":$width,"height":$height,"expires_at":"2026-09-06T09:12:44Z"}""",
        )

    private fun expired() = MockResponse().setResponseCode(410)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":"session_expired","detail":"gone"}""")

    private fun masksResponse(scores: List<Float> = listOf(0.97f)) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            scores.joinToString(
                prefix = """{"masks":[""",
                postfix = "]}",
            ) { """{"score":$it,"png":"${maskPng()}"}""" },
        )

    private fun maskPng(): String {
        val bitmap = Bitmap.createBitmap(UPLOAD_SIZE, UPLOAD_SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until UPLOAD_SIZE) {
            for (x in 0 until UPLOAD_SIZE) {
                bitmap.setPixel(x, y, if (x < UPLOAD_SIZE / 2) Color.WHITE else Color.BLACK)
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
        const val WORKING_SIZE = 64
        const val UPLOAD_SIZE = 32
    }
}
