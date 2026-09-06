package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.util.Base64
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * specs/segmentation.md §9. MockWebServer binds localhost only, which is the single network a
 * test may touch (CLAUDE.md hard limits).
 */
@RunWith(RobolectricTestRunner::class)
class Sam3ClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: Sam3Client
    private var config = Sam3Config(baseUrl = "", token = TOKEN)
    private val logger = RecordingLogger()

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, cause: Throwable?) {
            warnings += message
        }

        override fun error(tag: String, message: String, cause: Throwable?) {
            warnings += message
        }
    }

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = Sam3Config(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = TOKEN,
        )
        client = Sam3Client({ config }, dispatchers, logger = logger)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- routes ----------------------------------------------------------

    @Test
    fun `upload posts multipart and parses the session`() = runTest {
        server.enqueue(
            json(
                201,
                """{"image_id":"abc","width":2048,"height":1365,"expires_at":"2026-09-06T09:12:44Z"}""",
            ),
        )

        val outcome = client.upload(byteArrayOf(1, 2, 3), Sam3Client.JPEG_MEDIA_TYPE).success()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/images", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(request.body.readUtf8().contains("""name="file""""))
        assertEquals("abc", outcome.imageId)
        assertEquals(2048, outcome.width)
        assertEquals(1365, outcome.height)
    }

    @Test
    fun `points sends normalized coordinates, integer labels and the png format`() = runTest {
        server.enqueue(json(200, """{"masks":[]}"""))

        client.points(
            "abc",
            PointPrompt(
                points = listOf(PointF(0.51f, 0.42f), PointF(0.63f, 0.55f)),
                labels = listOf(true, false),
            ),
        ).success()

        val request = server.takeRequest()
        assertEquals("/v1/images/abc/segment/points", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.51, body.getJSONArray("points").getJSONArray(0).getDouble(0), 1e-6)
        assertEquals(0.42, body.getJSONArray("points").getJSONArray(0).getDouble(1), 1e-6)
        assertEquals(1, body.getJSONArray("labels").getInt(0))
        assertEquals(0, body.getJSONArray("labels").getInt(1))
        assertTrue(body.getBoolean("multimask"))
        assertEquals("png", body.getString("format"))
    }

    @Test
    fun `text sends the phrase with the documented defaults`() = runTest {
        server.enqueue(json(200, """{"count":0,"masks":[]}"""))

        val masks = client.text("abc", "사람").success()

        val request = server.takeRequest()
        assertEquals("/v1/images/abc/segment/text", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("사람", body.getString("prompt"))
        assertEquals(0.5, body.getDouble("threshold"), 1e-6)
        assertEquals(20, body.getInt("max_instances"))
        assertEquals("png", body.getString("format"))
        assertEquals(emptyList<RawMask>(), masks)
    }

    @Test
    fun `delete issues DELETE and never reports failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client.delete("abc")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/images/abc", request.path)
    }

    @Test
    fun `health is unauthenticated`() = runTest {
        server.enqueue(json(200, """{"status":"ok"}"""))

        client.health().success()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ---- mask decoding ---------------------------------------------------

    @Test
    fun `a grayscale mask png decodes to a binary ALPHA_8 bitmap`() = runTest {
        server.enqueue(json(200, """{"masks":[{"score":0.97,"png":"${maskPng()}"}]}"""))

        val masks = client.points("abc", fg()).success()

        assertEquals(1, masks.size)
        assertEquals(0.97f, masks[0].score, 1e-6f)
        val alpha = masks[0].alpha
        assertEquals(Bitmap.Config.ALPHA_8, alpha.config)
        assertEquals(MASK_SIZE, alpha.width)
        // The fixture is a white left half on black, so exactly half the pixels are set.
        assertEquals(0.5f, MaskBitmaps.coverage(alpha), 0.01f)
        assertEquals(MaskBitmaps.OPAQUE, MaskBitmaps.alphaAt(alpha, 2, 2))
        assertEquals(MaskBitmaps.CLEAR, MaskBitmaps.alphaAt(alpha, MASK_SIZE - 2, 2))
    }

    // ---- error mapping (specs/segmentation.md §4) ------------------------

    @Test
    fun `400 becomes Invalid and carries the server detail`() = runTest {
        server.enqueue(json(400, """{"error":"invalid_prompt","detail":"empty points"}"""))

        val failure = client.points("abc", fg()).failure()

        assertEquals(AppError.Invalid("empty points"), failure)
    }

    @Test
    fun `401 becomes Unauthorized`() = runTest {
        server.enqueue(json(401, """{"error":"unauthorized","detail":"bad token"}"""))

        assertEquals(AppError.Unauthorized, client.points("abc", fg()).failure())
    }

    @Test
    fun `410 is its own outcome, not a failure`() = runTest {
        server.enqueue(json(410, """{"error":"session_expired","detail":"gone"}"""))

        assertEquals(Sam3Outcome.SessionExpired, client.points("abc", fg()))
    }

    @Test
    fun `413 becomes TooLarge`() = runTest {
        server.enqueue(json(413, """{"error":"image_too_large","detail":"21MB"}"""))

        assertEquals(
            AppError.TooLarge,
            client.upload(byteArrayOf(1), Sam3Client.JPEG_MEDIA_TYPE).failure(),
        )
    }

    @Test
    fun `415 becomes Unsupported`() = runTest {
        server.enqueue(json(415, """{"error":"unsupported_media_type","detail":"gif"}"""))

        assertEquals(
            AppError.Unsupported,
            client.upload(byteArrayOf(1), Sam3Client.JPEG_MEDIA_TYPE).failure(),
        )
    }

    @Test
    fun `429 becomes Unavailable and is not retried`() = runTest {
        server.enqueue(
            json(429, """{"error":"rate_limited","detail":"slow down"}""")
                .setHeader("Retry-After", "30"),
        )

        assertEquals(AppError.Unavailable, client.points("abc", fg()).failure())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `503 becomes Unavailable`() = runTest {
        server.enqueue(json(503, """{"error":"not_ready","detail":"loading"}"""))

        assertEquals(AppError.Unavailable, client.health().failure())
    }

    @Test
    fun `an unparseable body is an Io failure, not a crash`() = runTest {
        server.enqueue(json(200, "not json at all"))

        assertTrue(client.points("abc", fg()).failure() is AppError.Io)
    }

    /**
     * The first device run was debugged from the *server's* log, because the app kept none:
     * a failed call surfaced as a Korean snackbar and nothing else. architecture.md §9 puts
     * logging behind `core:common`'s `Logger`; this is what makes it non-optional here.
     */
    @Test
    fun `a failed call is logged with the route and the mapped error`() = runTest {
        server.enqueue(json(503, """{"error":"not_ready","detail":"loading"}"""))

        client.points("abc", fg()).failure()

        val line = logger.warnings.single()
        assertTrue("route missing from '$line'", line.contains("/v1/images/abc/segment/points"))
        assertTrue("status missing from '$line'", line.contains("503"))
    }

    @Test
    fun `a successful call logs nothing`() = runTest {
        server.enqueue(json(200, """{"masks":[]}"""))

        client.points("abc", fg()).success()

        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun `an unconfigured base URL never reaches the network`() = runTest {
        config = config.copy(baseUrl = "")

        val failure = client.health().failure()

        assertTrue(failure is AppError.Invalid)
        assertEquals(0, server.requestCount)
    }

    // ---- helpers ---------------------------------------------------------

    private fun fg() = PointPrompt(listOf(PointF(0.5f, 0.5f)), listOf(true))

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    /** Mirrors the server's `Image.fromarray(mask * 255, mode="L")`: white is set, black is not. */
    private fun maskPng(): String {
        val bitmap = Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until MASK_SIZE) {
            for (x in 0 until MASK_SIZE) {
                bitmap.setPixel(x, y, if (x < MASK_SIZE / 2) Color.WHITE else Color.BLACK)
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun <T> Sam3Outcome<T>.success(): T = when (this) {
        is Sam3Outcome.Success -> value
        else -> throw AssertionError("expected success, got $this")
    }

    private fun <T> Sam3Outcome<T>.failure(): AppError = when (this) {
        is Sam3Outcome.Failure -> error
        else -> throw AssertionError("expected failure, got $this")
    }

    private companion object {
        const val TOKEN = "test-token"
        const val MASK_SIZE = 32
    }
}
