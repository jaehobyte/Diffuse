package com.diffuse.core.ai.topaz

import android.graphics.Bitmap
import com.diffuse.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * specs/topaz_enhancement.md §8. The wire, against MockWebServer on loopback only — no test
 * reaches Topaz. [api] plays `api.topazlabs.com`; [storage] plays the signed-URL host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopazHttpProviderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var api: MockWebServer
    private lateinit var storage: MockWebServer
    private lateinit var config: MutableStateFlow<TopazConfig>
    private lateinit var provider: TopazHttpProvider
    private var now = 0L

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        api = MockWebServer().apply { start() }
        storage = MockWebServer().apply { start() }
        config = MutableStateFlow(TopazConfig(apiKey = KEY, baseUrl = api.url("/image/v1").toString()))
        val okHttp = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        provider = TopazHttpProvider(
            config,
            TopazClient(dispatchers, okHttp),
            dispatchers,
            TopazPolling(
                firstDelayMs = 1,
                maxDelayMs = 2,
                deadlineMs = 60_000,
                maxTransientFailures = 2,
                cancelTimeoutMs = 2_000,
                clock = { now },
            ),
        )
    }

    @After
    fun tearDown() {
        api.shutdown()
        storage.shutdown()
    }

    // ---- the happy path ---------------------------------------------------

    @Test
    fun `an upscale submits once, polls, and streams the signed file without the key`() = runBlocking {
        enqueueSubmit()
        api.enqueue(json(200, """{"status":"Pending","progress":0}"""))
        api.enqueue(json(200, """{"status":"Processing","progress":40}"""))
        api.enqueue(json(200, """{"status":"Completed","progress":100,"credits":2}"""))
        enqueueDownload(jpeg(80, 60))
        val stages = mutableListOf<TopazStage>()

        val outcome = provider.enhance(upscale(), input(), output()) { stages += it }

        val result = (outcome as TopazOutcome.Success).value
        assertEquals(80, result.width)
        assertEquals(60, result.height)
        assertEquals(2, result.credits)
        assertTrue(result.file.isFile)
        assertFalse(File(result.file.path + ".part").exists())

        val submit = api.takeRequest()
        assertEquals("POST", submit.method)
        assertEquals("/image/v1/enhance/async", submit.path)
        assertEquals(KEY, submit.getHeader("X-API-Key"))
        val body = submit.body.readUtf8()
        assertTrue(body.contains(field("model", "Standard V2")))
        assertTrue(body.contains(field("output_format", "jpeg")))
        assertTrue(body.contains(field("output_width", "80")))
        assertTrue(body.contains(field("faceEnhancement", "false")))
        assertFalse(body.contains("output_height"))
        assertFalse(body.contains("faceEnhancementStrength"))
        assertTrue(body.contains("name=\"image\""))

        assertEquals("/image/v1/status/$PROCESS", api.takeRequest().path)
        api.takeRequest()
        api.takeRequest()
        assertEquals("/image/v1/download/$PROCESS", api.takeRequest().path)
        assertEquals(5, api.requestCount)

        val fetched = storage.takeRequest()
        assertNull(fetched.getHeader("X-API-Key"))
        assertTrue(stages.contains(TopazStage.Submitted(PROCESS)))
        assertTrue(stages.contains(TopazStage.Queued))
        assertTrue(stages.contains(TopazStage.Processing(40)))
        assertTrue(stages.contains(TopazStage.Downloading))
    }

    @Test
    fun `sharpen on Autopilot sends no strength and a manual one sends exactly one`() = runBlocking {
        enqueueSubmit(); enqueueCompleted(); enqueueDownload(jpeg(40, 30))
        provider.enhance(job(TopazPurpose.Sharpen), input(), output()) {}
        val autoRequest = api.takeRequest()
        assertEquals("/image/v1/sharpen/async", autoRequest.path)
        val auto = autoRequest.body.readUtf8()
        assertTrue(auto.contains(field("model", "Standard")))
        assertFalse(auto.contains("sharpenStrength"))
        assertFalse(auto.contains("output_width"))
        drain(2)

        enqueueSubmit(); enqueueCompleted(); enqueueDownload(jpeg(40, 30))
        provider.enhance(
            job(TopazPurpose.Sharpen, TopazOptions(scale = 1, sharpenStrength = 0.3f)),
            input(),
            output("b.jpg"),
        ) {}
        val manual = api.takeRequest()
        assertEquals("/image/v1/sharpen/async", manual.path)
        assertTrue(manual.body.readUtf8().contains(field("sharpenStrength", "0.30")))
    }

    @Test
    fun `face recovery is off by default, explicitly, and on sends both required values`() = runBlocking {
        enqueueSubmit(); enqueueCompleted(); enqueueDownload(jpeg(40, 30))
        provider.enhance(job(TopazPurpose.Restore, TopazOptions(scale = 1, faceRecovery = true)), input(), output()) {}

        val submit = api.takeRequest()
        assertEquals("/image/v1/enhance-gen/async", submit.path)
        val body = submit.body.readUtf8()
        assertTrue(body.contains(field("model", "Wonder")))
        assertTrue(body.contains(field("faceEnhancement", "true")))
        assertTrue(body.contains(field("faceEnhancementStrength", "0.5")))
        assertTrue(body.contains(field("faceEnhancementCreativity", "0.5")))
    }

    @Test
    fun `the lighting and restore purposes use their documented routes and models`() = runBlocking {
        val cases = listOf(
            TopazPurpose.Lighting to ("/image/v1/lighting/async" to "Adjust V2"),
            TopazPurpose.ColorBalance to ("/image/v1/lighting/async" to "White Balance"),
            TopazPurpose.Colorize to ("/image/v1/lighting/async" to "Colorize"),
            TopazPurpose.DustScratch to ("/image/v1/restore-gen/async" to "Dust-Scratch V2"),
            TopazPurpose.Denoise to ("/image/v1/denoise/async" to "Normal"),
            TopazPurpose.Creative to ("/image/v1/enhance-gen/async" to "Redefine"),
        )
        cases.forEachIndexed { index, (purpose, expected) ->
            enqueueSubmit(); enqueueCompleted(); enqueueDownload(jpeg(40, 30))
            val outcome = provider.enhance(job(purpose), input(), output("$index.jpg")) {}
            assertTrue("$purpose: $outcome", outcome is TopazOutcome.Success)
            val submit = api.takeRequest()
            assertEquals(expected.first, submit.path)
            val body = submit.body.readUtf8()
            assertTrue(body.contains(field("model", expected.second)))
            // Only the purposes whose pages document face recovery mention it at all.
            assertEquals(purpose.faceRecovery, body.contains("faceEnhancement"))
            drain(2)
        }
    }

    // ---- failures that end the job ----------------------------------------

    @Test
    fun `failed and cancelled statuses are never completion, and send no cancel`() = runBlocking {
        enqueueSubmit(); api.enqueue(json(200, """{"status":"Failed"}"""))
        assertEquals(failure(TopazFailure.JobFailed), provider.enhance(upscale(), input(), output()) {})

        enqueueSubmit(); api.enqueue(json(200, """{"status":"Cancelled"}"""))
        assertEquals(failure(TopazFailure.JobCancelled), provider.enhance(upscale(), input(), output()) {})

        delay(SETTLE_MS)
        assertEquals(4, api.requestCount)
        assertEquals(0, storage.requestCount)
    }

    @Test
    fun `an undocumented status is a bad response, and the job is cancelled remotely`() = runBlocking {
        enqueueSubmit()
        api.enqueue(json(200, """{"status":"Done"}"""))
        api.enqueue(MockResponse().setResponseCode(204))

        assertEquals(failure(TopazFailure.BadResponse), provider.enhance(upscale(), input(), output()) {})

        drain(2)
        val cancel = api.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", cancel.method)
        assertEquals("/image/v1/cancel/$PROCESS", cancel.path)
        assertEquals(KEY, cancel.getHeader("X-API-Key"))
    }

    @Test
    fun `malformed status JSON is not completion`() = runBlocking {
        enqueueSubmit()
        api.enqueue(json(200, """{"progress":"half"}"""))
        api.enqueue(MockResponse().setResponseCode(204))

        assertEquals(failure(TopazFailure.BadResponse), provider.enhance(upscale(), input(), output()) {})
        assertEquals(0, storage.requestCount)
    }

    @Test
    fun `a 429 on status waits and retries the poll, never the submit`() = runBlocking {
        enqueueSubmit()
        api.enqueue(json(429, """{"message":"slow down"}""").setHeader("Retry-After", "0"))
        enqueueCompleted()
        enqueueDownload(jpeg(80, 60))

        val outcome = provider.enhance(upscale(), input(), output()) {}

        assertTrue(outcome is TopazOutcome.Success)
        val methods = (1..api.requestCount).map { api.takeRequest().method }
        assertEquals(1, methods.count { it == "POST" })
    }

    @Test
    fun `too many transient status failures give up and cancel`() = runBlocking {
        enqueueSubmit()
        repeat(3) { api.enqueue(json(503, """{"message":"busy"}""")) }
        api.enqueue(MockResponse().setResponseCode(204))

        assertEquals(failure(TopazFailure.Server), provider.enhance(upscale(), input(), output()) {})
        drain(4)
        assertEquals("DELETE", api.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `the polling deadline times out and cancels remotely`() = runBlocking {
        enqueueSubmit()
        // The first status answer moves the clock past the deadline.
        api.enqueue(json(200, """{"status":"Processing","progress":10}"""))
        api.enqueue(MockResponse().setResponseCode(204))
        val outcome = async(Dispatchers.IO) {
            provider.enhance(upscale(), input(), output()) { stage ->
                if (stage is TopazStage.Processing) now = 120_000
            }
        }

        assertEquals(failure(TopazFailure.Timeout), outcome.await())
        drain(2)
        assertEquals("DELETE", api.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    // ---- submit is never re-sent -------------------------------------------

    @Test
    fun `402 on submit is insufficient credits, with one POST and nothing after`() = runBlocking {
        api.enqueue(json(402, """{"message":"Payment is required"}"""))

        assertEquals(failure(TopazFailure.InsufficientCredits), provider.enhance(upscale(), input(), output()) {})
        delay(SETTLE_MS)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `a lost submit response is unknown, and is not re-sent`() = runBlocking {
        api.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(failure(TopazFailure.SubmitUnknown), provider.enhance(upscale(), input(), output()) {})
        delay(SETTLE_MS)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `401 and 403 are both a key problem`() = runBlocking {
        api.enqueue(json(401, """{"message":"no"}"""))
        assertEquals(failure(TopazFailure.Unauthorized), provider.enhance(upscale(), input(), output()) {})
        api.enqueue(json(403, """{"message":"no"}"""))
        assertEquals(failure(TopazFailure.Unauthorized), provider.enhance(upscale(), input(), output()) {})
    }

    @Test
    fun `a redirect from the API is refused, so the key never follows it`() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/elsewhere")))

        assertEquals(failure(TopazFailure.BadResponse), provider.enhance(upscale(), input(), output()) {})
        delay(SETTLE_MS)
        assertEquals(0, storage.requestCount)
    }

    @Test
    fun `an offline host is offline`() = runBlocking {
        config.value = config.value.copy(baseUrl = "http://localhost:1/image/v1")

        assertEquals(failure(TopazFailure.Offline), provider.enhance(upscale(), input(), output()) {})
    }

    // ---- the downloaded file ----------------------------------------------

    @Test
    fun `a file of the wrong size is rejected and removed`() = runBlocking {
        enqueueSubmit(); enqueueCompleted(); enqueueDownload(jpeg(40, 30))
        val target = output()

        assertEquals(failure(TopazFailure.OutputInvalid), provider.enhance(upscale(), input(), target) {})
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a corrupt file is rejected`() = runBlocking {
        enqueueSubmit(); enqueueCompleted()
        api.enqueue(json(200, """{"download_url":"${storage.url("/out.jpg")}","head_url":"x","expiry":1}"""))
        storage.enqueue(MockResponse().setBody("not an image"))

        assertEquals(failure(TopazFailure.OutputInvalid), provider.enhance(upscale(), input(), output()) {})
    }

    @Test
    fun `the quickstart's url field is not the schema's, and is not trusted`() = runBlocking {
        enqueueSubmit(); enqueueCompleted()
        api.enqueue(json(200, """{"url":"${storage.url("/out.jpg")}"}"""))

        assertEquals(failure(TopazFailure.BadResponse), provider.enhance(upscale(), input(), output()) {})
        assertEquals(0, storage.requestCount)
    }

    @Test
    fun `the download stops at the byte ceiling whatever the header says`() = runBlocking {
        val client = TopazClient(dispatchers, OkHttpClient())
        storage.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))
        val target = folder.newFile("big.jpg")

        val outcome = client.download(storage.url("/big.jpg").toString(), target, maxBytes = 1024)

        assertEquals(failure(TopazFailure.TooLarge), outcome)
    }

    // ---- cancellation -----------------------------------------------------

    @Test
    fun `cancelling the caller stops polling and cancels the job under the starting key`() = runBlocking {
        enqueueSubmit()
        repeat(50) { api.enqueue(json(200, """{"status":"Processing","progress":5}""")) }
        val stages = mutableListOf<TopazStage>()
        val run = async(Dispatchers.IO) { provider.enhance(upscale(), input(), output()) { stages += it } }
        withTimeout(2_000) { while (stages.none { it is TopazStage.Processing }) delay(1) }

        config.value = config.value.copy(apiKey = "a-new-key", generation = 1)
        run.cancel()

        val cancel = withTimeout(3_000) {
            var request: RecordedRequest
            do request = api.takeRequest() while (request.method != "DELETE")
            request
        }
        assertEquals("/image/v1/cancel/$PROCESS", cancel.path)
        assertEquals(KEY, cancel.getHeader("X-API-Key"))
        assertEquals(0, storage.requestCount)
    }

    // ---- estimate ---------------------------------------------------------

    @Test
    fun `the estimate is priced on the output size and creates no job`() = runBlocking {
        api.enqueue(json(200, """{"credits":4,"duration":12}"""))

        val outcome = provider.estimate(upscale())

        assertEquals(TopazOutcome.Success(TopazEstimate(4, 12)), outcome)
        val request = api.takeRequest()
        assertEquals("/image/v1/estimate", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(field("input_width", "40")))
        assertTrue(body.contains(field("output_width", "80")))
        assertTrue(body.contains(field("output_height", "60")))
        assertTrue(body.contains(field("category", "Enhance")))
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `a generative estimate goes to the generative route`() = runBlocking {
        api.enqueue(json(200, """{"credits":1,"duration":1}"""))

        provider.estimate(job(TopazPurpose.DustScratch))

        val request = api.takeRequest()
        assertEquals("/image/v1/estimate-gen", request.path)
        assertTrue(request.body.readUtf8().contains(field("category", "Restore")))
    }

    @Test
    fun `an estimate failure is a failure, never zero`() = runBlocking {
        api.enqueue(json(500, """{"message":"x"}"""))

        assertEquals(failure(TopazFailure.Server), provider.estimate(upscale()))
    }

    @Test
    fun `nothing is sent without a key or for an unsendable job`() = runBlocking {
        config.value = config.value.copy(apiKey = "")
        assertEquals(failure(TopazFailure.NotConfigured), provider.enhance(upscale(), input(), output()) {})
        assertEquals(failure(TopazFailure.NotConfigured), provider.estimate(upscale()))

        config.value = config.value.copy(apiKey = KEY)
        val alpha = upscale().copy(inputHasAlpha = true)
        assertEquals(failure(TopazFailure.InputHasAlpha), provider.enhance(alpha, input(), output()) {})
        assertEquals(0, api.requestCount)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun upscale() = job(TopazPurpose.Upscale, TopazOptions(scale = 2))

    private fun job(purpose: TopazPurpose, options: TopazOptions = TopazOptions.defaultsFor(purpose)) =
        TopazJob(purpose, options, inputWidth = 40, inputHeight = 30)

    private fun input(): File = folder.newFile().apply { writeBytes(jpeg(40, 30)) }

    private fun output(name: String = "out-${System.nanoTime()}.jpg") = File(folder.root, name)

    private fun enqueueSubmit() =
        api.enqueue(json(200, """{"process_id":"$PROCESS","source_id":"s","eta":1}"""))

    private fun enqueueCompleted() =
        api.enqueue(json(200, """{"status":"Completed","progress":100,"credits":1}"""))

    private fun enqueueDownload(bytes: ByteArray) {
        api.enqueue(
            json(200, """{"download_url":"${storage.url("/signed/out.jpg?sig=abc")}","head_url":"h","expiry":1}"""),
        )
        storage.enqueue(MockResponse().setBody(Buffer().write(bytes)))
    }

    private fun drain(count: Int) = repeat(count) { api.takeRequest(2, TimeUnit.SECONDS) }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun failure(failure: TopazFailure) = TopazOutcome.Failure(failure)

    private fun field(name: String, value: String) = "name=\"$name\"\r\nContent-Length: ${value.length}\r\n\r\n$value"

    private companion object {
        const val KEY = "topaz-secret"
        const val PROCESS = "0b0e8a3c-1111-4222-8333-944445555666"
        const val SETTLE_MS = 100L

        fun jpeg(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
        }
    }
}
