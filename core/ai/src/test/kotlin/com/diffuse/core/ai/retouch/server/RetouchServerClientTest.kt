package com.diffuse.core.ai.retouch.server

import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * specs/skin_retouch_pipeline.md §8.1, the client half of the contract test. The server half is
 * `server/retouch/tests`. MockWebServer binds localhost only.
 */
class RetouchServerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RetouchServerClient
    private lateinit var config: RetouchServerConfig

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.IO
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = RetouchServerConfig(server.url("/").toString().trimEnd('/'), TOKEN)
        client = RetouchServerClient({ config }, dispatchers, OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    // ---- health ---------------------------------------------------------------------------

    @Test
    fun `health sends the bearer token and reads kinds with their engines`() = runTest {
        server.enqueue(json(200, READY))

        val health = (client.health() as Result.Success).value

        assertEquals("Bearer $TOKEN", server.takeRequest().getHeader("Authorization"))
        assertEquals(mapOf(SkinRetouchKind.Blemish to ENGINE), health.engines)
    }

    @Test
    fun `a loading server is unavailable and a wrong token is unauthorized`() = runTest {
        server.enqueue(json(503, """{"contract_version":1,"status":"loading","supported_kinds":[],"engines":{}}"""))
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        server.enqueue(json(403, """{"error":"forbidden"}"""))

        assertEquals(Result.Failure(AppError.Unavailable), client.health())
        assertEquals(Result.Failure(AppError.Unauthorized), client.health())
        assertEquals(Result.Failure(AppError.Unauthorized), client.health())
    }

    @Test
    fun `a 200 that says loading is still not ready`() = runTest {
        server.enqueue(
            json(
                200,
                """{"contract_version":1,"status":"loading","supported_kinds":["blemish"],""" +
                    """"engines":{"blemish":"x"}}""",
            ),
        )

        assertEquals(Result.Failure(AppError.Unavailable), client.health())
    }

    @Test
    fun `evaluation engines are never supported kinds`() = runTest {
        server.enqueue(
            json(
                200,
                """{"contract_version":1,"status":"ready","supported_kinds":[],"engines":{},""" +
                    """"evaluation_engines":{"blemish":"$ENGINE","shine":"shine@1"}}""",
            ),
        )

        assertEquals(emptySet<SkinRetouchKind>(), (client.health() as Result.Success).value.supportedKinds)
    }

    @Test
    fun `a kind without an engine version is not supported`() = runTest {
        server.enqueue(
            json(
                200,
                """{"contract_version":1,"status":"ready","supported_kinds":["blemish","shine"],""" +
                    """"engines":{"blemish":"$ENGINE"}}""",
            ),
        )

        assertEquals(setOf(SkinRetouchKind.Blemish), (client.health() as Result.Success).value.supportedKinds)
    }

    @Test
    fun `another contract version or another service is an address problem`() = runTest {
        server.enqueue(json(200, """{"contract_version":2,"status":"ready","supported_kinds":[],"engines":{}}"""))
        server.enqueue(json(404, """{"detail":"Not Found"}"""))

        assertTrue(client.health().isInvalid())
        assertEquals(
            Result.Failure(AppError.Invalid(RetouchServerClient.NOT_A_RETOUCH_SERVER)),
            client.health(),
        )
    }

    @Test
    fun `blank and malformed addresses never reach the network`() = runTest {
        assertEquals(
            Result.Failure(AppError.Invalid(RetouchServerClient.NO_SERVER_ADDRESS)),
            client.health(RetouchServerConfig("")),
        )
        assertEquals(
            Result.Failure(AppError.Invalid(RetouchServerClient.INVALID_SERVER_ADDRESS)),
            client.health(RetouchServerConfig("not a url")),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unreachable server is an io failure`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client.health()

        assertTrue("$result", result is Result.Failure && result.error is AppError.Io)
    }

    // ---- request --------------------------------------------------------------------------

    @Test
    fun `the request is multipart with metadata an rgba image and a binary gray mask`() = runTest {
        server.enqueue(noChange(REQUEST_ID))
        val roi = roi()

        client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi)

        val recorded = server.takeRequest()
        assertEquals("/v1/retouch", recorded.requestUrl?.encodedPath)
        assertEquals("Bearer $TOKEN", recorded.getHeader("Authorization"))
        val parts = parts(recorded.getHeader("Content-Type")!!, recorded.body)
        assertEquals(setOf("metadata", "image", "allowed_mask"), parts.keys)
        val metadata = Json.parseToJsonElement(String(parts.getValue("metadata"))).jsonObject
        assertEquals(REQUEST_ID, metadata.getValue("request_id").jsonPrimitive.content)
        assertEquals(1, metadata.getValue("contract_version").jsonPrimitive.int)
        assertEquals("dark_circles", metadata.getValue("kind").jsonPrimitive.content)
        assertEquals(ENGINE, metadata.getValue("expected_engine_version").jsonPrimitive.content)
        assertEquals(W, metadata.getValue("width").jsonPrimitive.int)
        assertEquals(H, metadata.getValue("height").jsonPrimitive.int)

        val image = RetouchPng.decode(parts.getValue("image"), MAX)!!
        assertEquals(RetouchPng.COLOR_RGBA, image.colorType)
        assertEquals(0x40, image.samples[3].toInt() and 0xFF)
        val mask = RetouchPng.decode(parts.getValue("allowed_mask"), MAX)!!
        assertEquals(RetouchPng.COLOR_GRAY, mask.colorType)
        assertArrayEquals(ByteArray(W * H) { if (roi.allowed[it]) -1 else 0 }, mask.samples)
    }

    // ---- response -------------------------------------------------------------------------

    @Test
    fun `no_change is metadata only`() = runTest {
        server.enqueue(noChange(REQUEST_ID))

        val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi())

        assertEquals(Result.Success(RetouchServerClient.Correction.NoChange(ENGINE)), result)
    }

    @Test
    fun `a corrected answer is cut to the allowance and the opaque pixels, with the input alpha`() = runTest {
        val roi = roi()
        // The server claims every pixel changed to white, alpha included.
        server.enqueue(
            corrected(
                REQUEST_ID,
                candidate = IntArray(W * H) { 0xFFFFFFFF.toInt() },
                support = ByteArray(W * H) { -1 },
            ),
        )

        val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi)

        val corrected = (result as Result.Success).value as RetouchServerClient.Correction.Corrected
        for (i in 0 until W * H) {
            val input = roi.argb[i]
            val mayChange = roi.allowed[i] && input ushr 24 != 0
            assertEquals("support at $i", mayChange, corrected.support[i])
            val expected = if (mayChange) (input and 0xFF000000.toInt()) or 0xFFFFFF else input
            assertEquals("candidate at $i", expected, corrected.candidate[i])
        }
    }

    @Test
    fun `a support that survives nothing after the cut is no change`() = runTest {
        val roi = roi()
        server.enqueue(
            corrected(
                REQUEST_ID,
                candidate = IntArray(W * H) { 0xFFFFFFFF.toInt() },
                support = ByteArray(W * H) { if (roi.allowed[it]) 0 else -1 },
            ),
        )

        val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi)

        assertEquals(Result.Success(RetouchServerClient.Correction.NoChange(ENGINE)), result)
    }

    @Test
    fun `echo mismatches are not a success`() = runTest {
        val cases = listOf(
            metadata("other-id"),
            metadata(REQUEST_ID, kind = "shine"),
            metadata(REQUEST_ID, contract = 2),
            metadata(REQUEST_ID, width = W + 1),
        )
        cases.forEach { server.enqueue(multipart("metadata" to it.toByteArray())) }

        cases.forEach {
            assertTrue(it, client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi()).isInvalid())
        }
    }

    @Test
    fun `an engine version other than the one asked for is unavailable so the provider re-probes`() = runTest {
        server.enqueue(multipart("metadata" to metadata(REQUEST_ID, engine = "other").toByteArray()))

        val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi())

        assertEquals(Result.Failure(AppError.Unavailable), result)
    }

    @Test
    fun `wrong channels sizes non-binary supports and missing parts are rejected`() = runTest {
        val gray = RetouchPng.encodeGray(W, H, ByteArray(W * H))
        val rgba = RetouchPng.encodeRgba(W, H, IntArray(W * H))
        val meta = metadata(REQUEST_ID, outcome = "corrected").toByteArray()
        val narrow = RetouchPng.encodeRgba(W - 1, H, IntArray((W - 1) * H))
        val notBinary = RetouchPng.encodeGray(W, H, ByteArray(W * H) { 7 })
        val responses = listOf(
            multipart("metadata" to meta, "candidate" to gray, "change_support" to gray),
            multipart("metadata" to meta, "candidate" to rgba, "change_support" to rgba),
            multipart("metadata" to meta, "candidate" to narrow, "change_support" to gray),
            multipart("metadata" to meta, "candidate" to rgba, "change_support" to notBinary),
            multipart("metadata" to meta, "candidate" to rgba),
            multipart("metadata" to metadata(REQUEST_ID).toByteArray(), "candidate" to rgba, "change_support" to gray),
            multipart("metadata" to meta, "candidate" to "junk".toByteArray(), "change_support" to gray),
        )
        responses.forEach(server::enqueue)

        responses.indices.forEach { index ->
            val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi())
            assertTrue("case $index: $result", result.isInvalid())
        }
    }

    @Test
    fun `status codes map to the errors the sheet tells apart`() = runTest {
        val table = listOf(
            400 to AppError.Invalid("server rejected the request: invalid_request"),
            401 to AppError.Unauthorized,
            403 to AppError.Unauthorized,
            409 to AppError.Unavailable,
            413 to AppError.TooLarge,
            422 to AppError.Unsupported,
            429 to AppError.Unavailable,
            503 to AppError.Unavailable,
            504 to AppError.Unavailable,
        )
        table.forEach { (code, _) -> server.enqueue(json(code, """{"error":"invalid_request"}""")) }

        table.forEach { (code, error) ->
            val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi())
            assertEquals("HTTP $code", Result.Failure(error), result)
        }
    }

    @Test
    fun `the input arrays are not modified`() = runTest {
        val roi = roi()
        val before = roi.argb.copyOf()
        server.enqueue(corrected(REQUEST_ID, IntArray(W * H) { -1 }, ByteArray(W * H) { -1 }))

        client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi)

        assertArrayEquals(before, roi.argb)
    }

    @Test
    fun `an roi past the pixel limit is too large before anything is sent`() = runTest {
        val roi = RetouchServerClient.Roi(4097, 1, IntArray(4097), BooleanArray(4097))

        val result = client.retouch(config, REQUEST_ID, SkinRetouchKind.DarkCircles, ENGINE, roi)

        assertEquals(Result.Failure(AppError.TooLarge), result)
        assertEquals(0, server.requestCount)
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Left half allowed; the last column transparent, so the alpha cut is exercised too. */
    private fun roi(): RetouchServerClient.Roi {
        val argb = IntArray(W * H) { index ->
            val x = index % W
            when {
                x == W - 1 -> 0x00336699
                index == 0 -> 0x40C08060
                else -> 0xFFC08060.toInt()
            }
        }
        val allowed = BooleanArray(W * H) { it % W < W / 2 + 1 }
        return RetouchServerClient.Roi(W, H, argb, allowed)
    }

    private fun Result<*>.isInvalid() = this is Result.Failure && error is AppError.Invalid

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Suppress("LongParameterList")
    private fun metadata(
        requestId: String,
        kind: String = "dark_circles",
        contract: Int = 1,
        width: Int = W,
        engine: String = ENGINE,
        outcome: String = "no_change",
    ) = """{"request_id":"$requestId","contract_version":$contract,"kind":"$kind",""" +
        """"engine_version":"$engine","outcome":"$outcome","width":$width,"height":$H,"timing_ms":{}}"""

    private fun noChange(requestId: String) = multipart("metadata" to metadata(requestId).toByteArray())

    private fun corrected(requestId: String, candidate: IntArray, support: ByteArray) = multipart(
        "metadata" to metadata(requestId, outcome = "corrected").toByteArray(),
        "candidate" to RetouchPng.encodeRgba(W, H, candidate),
        "change_support" to RetouchPng.encodeGray(W, H, support),
    )

    private fun multipart(vararg parts: Pair<String, ByteArray>): MockResponse {
        val body = Buffer()
        parts.forEach { (name, bytes) ->
            body.writeUtf8("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
            body.write(bytes)
            body.writeUtf8("\r\n")
        }
        body.writeUtf8("--$BOUNDARY--\r\n")
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .setBody(body)
    }

    private fun parts(contentType: String, body: Buffer): Map<String, ByteArray> {
        val boundary = contentType.substringAfter("boundary=")
        val out = mutableMapOf<String, ByteArray>()
        MultipartReader(body, boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val name = Regex("name=\"([^\"]*)\"").find(part.headers["Content-Disposition"]!!)!!.groupValues[1]
                out[name] = part.body.readByteArray()
            }
        }
        return out
    }

    private companion object {
        const val TOKEN = "test-token"
        const val ENGINE = "blemish/test@1"
        const val REQUEST_ID = "req-1"
        const val W = 6
        const val H = 4
        const val MAX = 16_777_216L
        const val BOUNDARY = "b0undary"
        val READY = """{"contract_version":1,"status":"ready","supported_kinds":["blemish"],""" +
            """"engines":{"blemish":"$ENGINE"}}"""
    }
}
