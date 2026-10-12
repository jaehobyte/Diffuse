package com.diffuse.core.ai.retouch.server

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CorrectionOutcome
import com.diffuse.core.ai.ExecutionLocation
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/skin_retouch_pipeline.md §2, §8; work/tasks.md requirement 18. The provider over a real
 * client and real settings, against localhost.
 */
@RunWith(RobolectricTestRunner::class)
class RetouchServerSkinRetouchProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val logged = mutableListOf<String>()

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    private val logger = object : Logger {
        override fun debug(tag: String, message: String) {
            logged += message
        }

        override fun warn(tag: String, message: String, cause: Throwable?) {
            logged += message
        }

        override fun error(tag: String, message: String, cause: Throwable?) {
            logged += message
        }
    }

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a clean install has no address, probes nothing and supports nothing`() = runBlocking<Unit> {
        val provider = provider(settings(RetouchServerConfig("")))

        assertEquals(
            Availability.Unavailable(AppError.Invalid(RetouchServerSkinRetouchProvider.NO_SERVER)),
            provider.availability.value,
        )
        assertEquals(emptySet<SkinRetouchKind>(), provider.supportedKinds.value)
        assertEquals(ExecutionLocation.RetouchServer, provider.executionLocation)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the kinds are what health enabled, and a save of the same settings re-probes`() = runBlocking<Unit> {
        server.enqueue(ready("blemish"))
        val settings = settings(RetouchServerConfig(base(), TOKEN))
        val provider = provider(settings)
        provider.awaitAnswer()
        assertEquals(setOf(SkinRetouchKind.Blemish), provider.supportedKinds.value)

        server.enqueue(ready("blemish", "shine"))
        settings.update(base(), TOKEN)

        withTimeout(WAIT_MS) { provider.supportedKinds.first { it.size == 2 } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a kind the server did not enable is unsupported and uploads nothing`() = runBlocking<Unit> {
        server.enqueue(ready("blemish"))
        val provider = readyProvider()

        val result = provider.prepare(roi(), allowed(), SkinRetouchKind.ShavingShadow)

        assertEquals(Result.Failure(AppError.Unsupported), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an empty allowance is no change without an upload`() = runBlocking<Unit> {
        server.enqueue(ready("blemish"))
        val provider = readyProvider()

        val prepared = provider.prepare(roi(), MaskBitmaps.empty(SIZE, SIZE), SkinRetouchKind.Blemish)

        assertEquals(CorrectionOutcome.NoChange, (prepared as Result.Success).value.outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `one prepare is one upload and returns a binary support inside the allowance`() = runBlocking<Unit> {
        server.enqueue(ready("blemish"))
        val provider = readyProvider()
        answerCorrectedEverywhere()
        val roi = roi()
        val before = roi.copy(Bitmap.Config.ARGB_8888, false)
        val allowed = allowed()

        val prepared = (provider.prepare(roi, allowed, SkinRetouchKind.Blemish) as Result.Success).value

        assertEquals(2, server.requestCount)
        assertEquals(CorrectionOutcome.Corrected, prepared.outcome)
        assertEquals(ENGINE, prepared.engineVersion)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val inside = MaskBitmaps.alphaAt(allowed, x, y) == MaskBitmaps.OPAQUE
                val support = MaskBitmaps.alphaAt(prepared.changeSupport, x, y)
                assertEquals(if (inside) MaskBitmaps.OPAQUE else MaskBitmaps.CLEAR, support)
                assertEquals(before.getPixel(x, y), roi.getPixel(x, y))
                if (!inside) assertEquals(before.getPixel(x, y), prepared.candidate.getPixel(x, y))
            }
        }
        assertTrue(logged.none { it.contains(TOKEN) })
    }

    @Test
    fun `a rejected token is unauthorized and re-probes once`() = runBlocking<Unit> {
        server.enqueue(ready("blemish"))
        val provider = readyProvider()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        val result = provider.prepare(roi(), allowed(), SkinRetouchKind.Blemish)

        assertEquals(Result.Failure(AppError.Unauthorized), result)
        withTimeout(WAIT_MS) { provider.availability.first { it == Availability.Unavailable(AppError.Unauthorized) } }
        assertEquals(3, server.requestCount)
        assertFalse(provider.supportedKinds.value.contains(SkinRetouchKind.Blemish))
    }

    @Test
    fun `a server that was not ready recovers on refresh with the same settings`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"contract_version":1,"status":"loading","supported_kinds":[],"engines":{}}"""),
        )
        val provider = provider(settings(RetouchServerConfig(base(), TOKEN)))
        provider.awaitAnswer()
        assertEquals(Result.Failure(AppError.Unavailable), provider.prepare(roi(), allowed(), SkinRetouchKind.Blemish))

        server.enqueue(ready("blemish"))
        provider.refresh()

        withTimeout(WAIT_MS) { provider.availability.first { it == Availability.Ready } }
        assertEquals(setOf(SkinRetouchKind.Blemish), provider.supportedKinds.value)
    }

    // ---- fixtures ------------------------------------------------------------------------

    private suspend fun readyProvider(): RetouchServerSkinRetouchProvider {
        val provider = provider(settings(RetouchServerConfig(base(), TOKEN)))
        withTimeout(WAIT_MS) { provider.availability.first { it == Availability.Ready } }
        return provider
    }

    private fun base() = server.url("/").toString()

    private fun settings(defaults: RetouchServerConfig) = RetouchServerSettings(context, defaults)

    private fun provider(settings: RetouchServerSettings) = RetouchServerSkinRetouchProvider(
        RetouchServerClient(settings, dispatchers, OkHttpClient()),
        settings,
        dispatchers,
        logger,
    )

    private suspend fun RetouchServerSkinRetouchProvider.awaitAnswer() {
        withTimeout(WAIT_MS) { checking.first { !it } }
    }

    private fun roi(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFFC08060.toInt())
    }

    private fun allowed(): Bitmap = MaskBitmaps.circle(SIZE, SIZE, SIZE / 2f, SIZE / 2f, SIZE / 3f)

    private fun ready(vararg kinds: String): MockResponse {
        val engines = kinds.joinToString(",") { "\"$it\":\"$ENGINE\"" }
        val list = kinds.joinToString(",") { "\"$it\"" }
        return MockResponse().setResponseCode(200)
            .setBody("""{"contract_version":1,"status":"ready","supported_kinds":[$list],"engines":{$engines}}""")
    }

    /** Echoes whatever request id the provider generated: the dispatcher reads it off the request. */
    private fun answerCorrectedEverywhere() {
        val request = server.takeRequest() // the health probe already answered
        check(request.path == "/health")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val text = request.body.readUtf8()
                val id = Regex("\"request_id\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                val body = Buffer()
                fun part(name: String, bytes: ByteArray) {
                    body.writeUtf8("--b\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    body.write(bytes)
                    body.writeUtf8("\r\n")
                }
                part(
                    "metadata",
                    ("""{"request_id":"$id","contract_version":1,"kind":"blemish","engine_version":"$ENGINE",""" +
                        """"outcome":"corrected","width":$SIZE,"height":$SIZE}""").toByteArray(),
                )
                part("candidate", RetouchPng.encodeRgba(SIZE, SIZE, IntArray(SIZE * SIZE) { -1 }))
                part("change_support", RetouchPng.encodeGray(SIZE, SIZE, ByteArray(SIZE * SIZE) { -1 }))
                body.writeUtf8("--b--\r\n")
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "multipart/form-data; boundary=b").setBody(body)
            }
        }
    }

    private companion object {
        const val PREFS = "retouch_server_settings"
        const val TOKEN = "t0ken-secret"
        const val ENGINE = "blemish/test@1"
        const val SIZE = 24
        const val WAIT_MS = 10_000L
    }
}
