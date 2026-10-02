package com.diffuse.core.ai.retouch.server

import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.gemini.await
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/skin_retouch_pipeline.md §8.1, one request per face ROI and kind. Everything the contract
 * says about bytes is here — the multipart names, the PNG shapes, the echo checks — so the
 * provider above it deals only in pixels and `AppError`s.
 *
 * The response is **not trusted**: every field is matched against the request, and the support is
 * cut back to what was allowed and to the ROI's own opaque pixels, with the candidate restored to
 * the input everywhere else, before anything leaves this class (§8.1 "클라이언트 검증").
 */
internal class RetouchServerClient(
    private val configSource: RetouchServerConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient = OkHttpClient(),
    private val logger: Logger? = null,
) {

    data class Health(val engines: Map<SkinRetouchKind, String>) {
        val supportedKinds: Set<SkinRetouchKind> get() = engines.keys
    }

    /**
     * One ROI as the wire sees it. [argb] are straight-alpha colour ints; [allowed] is the
     * allowance the app built, one flag per pixel. Neither is modified.
     */
    class Roi(val width: Int, val height: Int, val argb: IntArray, val allowed: BooleanArray)

    sealed interface Correction {
        val engineVersion: String

        data class NoChange(override val engineVersion: String) : Correction

        /** [candidate] equals the input outside [support], alpha included, everywhere. */
        class Corrected(
            override val engineVersion: String,
            val candidate: IntArray,
            val support: BooleanArray,
        ) : Correction
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** §8.1: connect 10 s, and 60 s for the whole call — longer than any server-side deadline. */
    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** §8.1: a short, finite probe of its own. */
    private val probeHttp: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    suspend fun health(config: RetouchServerConfig = configSource.current()): Result<Health> =
        withContext(dispatchers.io) {
            addressError(config)?.let { return@withContext Result.Failure(it) }
            val request = Request.Builder().url("${config.baseUrl}$HEALTH_PATH").get()
                .authorized(config).build()
            try {
                probeHttp.newCall(request).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) readHealth(body) else statusFailure(response, body)
                }
            } catch (e: IOException) {
                logger?.warn(TAG, "GET $HEALTH_PATH unreachable", e)
                Result.Failure(AppError.Io(e))
            }
        }

    @Suppress("LongParameterList")
    suspend fun retouch(
        config: RetouchServerConfig,
        requestId: String,
        kind: SkinRetouchKind,
        expectedEngineVersion: String,
        roi: Roi,
    ): Result<Correction> = withContext(dispatchers.io) {
        addressError(config)?.let { return@withContext Result.Failure(it) }
        require(roi.argb.size == roi.width * roi.height && roi.allowed.size == roi.argb.size) {
            "roi arrays are not ${roi.width}x${roi.height}"
        }
        if (roi.width.toLong() * roi.height > MAX_PIXELS || maxOf(roi.width, roi.height) > MAX_SIDE) {
            return@withContext Result.Failure(AppError.TooLarge)
        }
        val body = withContext(dispatchers.default) { requestBody(requestId, kind, expectedEngineVersion, roi) }
        if (body.contentLength() > MAX_BODY_BYTES) return@withContext Result.Failure(AppError.TooLarge)
        coroutineContext.ensureActive()

        val request = Request.Builder().url("${config.baseUrl}$RETOUCH_PATH").post(body)
            .authorized(config).build()
        try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.let { readBounded(it.source(), ERROR_BODY_BYTES) }
                        ?.toString(Charsets.UTF_8)
                    return@withContext statusFailure(response, text.orEmpty())
                }
                val parts = readParts(response)
                    ?: return@withContext invalidResponse(requestId, "unreadable multipart")
                withContext(dispatchers.default) {
                    verify(parts, requestId, kind, expectedEngineVersion, roi)
                }
            }
        } catch (e: IOException) {
            logger?.warn(TAG, "POST $RETOUCH_PATH $requestId ${kind.wireName} failed", e)
            Result.Failure(AppError.Io(e))
        }
    }

    private fun requestBody(
        requestId: String,
        kind: SkinRetouchKind,
        expectedEngineVersion: String,
        roi: Roi,
    ): MultipartBody {
        val metadata = buildJsonObject {
            put("request_id", requestId)
            put("contract_version", CONTRACT_VERSION)
            put("kind", kind.wireName)
            put("expected_engine_version", expectedEngineVersion)
            put("width", roi.width)
            put("height", roi.height)
        }.toString()
        val mask = ByteArray(roi.allowed.size) { if (roi.allowed[it]) ON else OFF }
        return MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(PART_METADATA, null, metadata.toRequestBody(JSON_TYPE.toMediaType()))
            .addFormDataPart(
                PART_IMAGE,
                "image.png",
                RetouchPng.encodeRgba(roi.width, roi.height, roi.argb).toRequestBody(PNG_TYPE.toMediaType()),
            )
            .addFormDataPart(
                PART_ALLOWED,
                "allowed_mask.png",
                RetouchPng.encodeGray(roi.width, roi.height, mask).toRequestBody(PNG_TYPE.toMediaType()),
            )
            .build()
    }

    /** Each part name once, the whole response under the §8.1 limit; null for anything else. */
    @Suppress("ReturnCount", "NestedBlockDepth")
    private fun readParts(response: Response): Map<String, ByteArray>? {
        val body = response.body ?: return null
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val parts = mutableMapOf<String, ByteArray>()
        var total = 0L
        return try {
            MultipartReader(body).use { reader ->
                while (true) {
                    val part = reader.nextPart() ?: break
                    val name = partName(part.headers["Content-Disposition"]) ?: return null
                    val bytes = readBounded(part.body, MAX_BODY_BYTES - total) ?: return null
                    total += bytes.size
                    if (parts.put(name, bytes) != null) return null
                }
            }
            parts
        } catch (_: ProtocolException) {
            null
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList")
    private fun verify(
        parts: Map<String, ByteArray>,
        requestId: String,
        kind: SkinRetouchKind,
        expectedEngineVersion: String,
        roi: Roi,
    ): Result<Correction> {
        val metadata = parts[PART_METADATA]?.let { parseObject(String(it, Charsets.UTF_8)) }
            ?: return invalidResponse(requestId, "no metadata")
        val echoed = metadata.string("request_id") == requestId &&
            metadata.int("contract_version") == CONTRACT_VERSION &&
            metadata.string("kind") == kind.wireName &&
            metadata.int("width") == roi.width && metadata.int("height") == roi.height
        if (!echoed) return invalidResponse(requestId, "metadata does not match the request")
        if (metadata.string("engine_version") != expectedEngineVersion) {
            // The server swapped engines between the probe and this call; the provider re-probes.
            return Result.Failure(AppError.Unavailable)
        }
        return when (metadata.string("outcome")) {
            OUTCOME_NO_CHANGE ->
                if (parts.keys == setOf(PART_METADATA)) {
                    Result.Success(Correction.NoChange(expectedEngineVersion))
                } else {
                    invalidResponse(requestId, "no_change with pixels")
                }
            OUTCOME_CORRECTED -> corrected(parts, requestId, expectedEngineVersion, roi)
            else -> invalidResponse(requestId, "unknown outcome")
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun corrected(
        parts: Map<String, ByteArray>,
        requestId: String,
        engineVersion: String,
        roi: Roi,
    ): Result<Correction> {
        if (parts.keys != setOf(PART_METADATA, PART_CANDIDATE, PART_SUPPORT)) {
            return invalidResponse(requestId, "corrected without exactly candidate and support")
        }
        val candidatePng = RetouchPng.decode(parts.getValue(PART_CANDIDATE), MAX_PIXELS)
        val supportPng = RetouchPng.decode(parts.getValue(PART_SUPPORT), MAX_PIXELS)
        val shaped = candidatePng != null && supportPng != null &&
            candidatePng.colorType == RetouchPng.COLOR_RGBA &&
            supportPng.colorType == RetouchPng.COLOR_GRAY &&
            candidatePng.width == roi.width && candidatePng.height == roi.height &&
            supportPng.width == roi.width && supportPng.height == roi.height
        if (!shaped) return invalidResponse(requestId, "candidate or support has the wrong shape")
        val supportSamples = supportPng!!.samples
        if (supportSamples.any { it != ON && it != OFF }) {
            return invalidResponse(requestId, "support is not binary")
        }

        val samples = candidatePng!!.samples
        val candidate = roi.argb.copyOf()
        val support = BooleanArray(roi.argb.size)
        var changed = false
        for (i in roi.argb.indices) {
            val input = roi.argb[i]
            // §8.1: cut back to the allowance and the ROI's own opaque pixels, whatever came back.
            if (supportSamples[i] != ON || !roi.allowed[i] || input ushr ALPHA_SHIFT == 0) continue
            val at = i * RGBA_CHANNELS
            val rgb = (samples[at].toInt() and BYTE_MASK shl RED_SHIFT) or
                (samples[at + 1].toInt() and BYTE_MASK shl GREEN_SHIFT) or
                (samples[at + 2].toInt() and BYTE_MASK)
            // Alpha is always the input's (§2): a correction never changes what is transparent.
            candidate[i] = (input and ALPHA_MASK) or rgb
            support[i] = true
            changed = true
        }
        return Result.Success(
            if (changed) {
                Correction.Corrected(engineVersion, candidate, support)
            } else {
                Correction.NoChange(engineVersion)
            },
        )
    }

    @Suppress("ReturnCount")
    private fun readHealth(body: String): Result<Health> {
        val root = parseObject(body) ?: return Result.Failure(AppError.Invalid(NOT_A_RETOUCH_SERVER))
        if (root.int("contract_version") != CONTRACT_VERSION) {
            return Result.Failure(AppError.Invalid(UNSUPPORTED_CONTRACT))
        }
        if (root.string("status") != STATUS_READY) return Result.Failure(AppError.Unavailable)
        val engines = root["engines"] as? JsonObject
        val kinds = (root["supported_kinds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::skinRetouchKindOf) }
            .orEmpty()
        val versions = kinds.mapNotNull { kind ->
            engines?.get(kind.wireName)?.let { it as? JsonPrimitive }?.content
                ?.takeIf { it.isNotBlank() }?.let { kind to it }
        }.toMap()
        return Result.Success(Health(versions))
    }

    /** §8.1's status table. The body's `error` code is logged, never shown. */
    private fun <T> statusFailure(response: Response, body: String): Result<T> {
        val code = parseObject(body)?.string("error").orEmpty()
        logger?.warn(TAG, "${response.request.url.encodedPath} -> ${response.code} $code")
        val error = when (response.code) {
            HTTP_BAD_REQUEST -> AppError.Invalid("server rejected the request: $code")
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> AppError.Unauthorized
            HTTP_NOT_FOUND -> AppError.Invalid(NOT_A_RETOUCH_SERVER)
            HTTP_PAYLOAD_TOO_LARGE -> AppError.TooLarge
            HTTP_UNPROCESSABLE -> AppError.Unsupported
            // 409 engine_mismatch, 429 overloaded, 503 not_ready, 504 timeout and other 5xx: the
            // server is there and cannot answer this now. The provider re-probes; the user retries.
            else -> AppError.Unavailable
        }
        return Result.Failure(error)
    }

    private fun <T> invalidResponse(requestId: String, reason: String): Result<T> {
        logger?.warn(TAG, "response for $requestId rejected: $reason")
        return Result.Failure(AppError.Invalid(INVALID_RESPONSE))
    }

    private fun addressError(config: RetouchServerConfig): AppError? = when {
        !config.isConfigured -> AppError.Invalid(NO_SERVER_ADDRESS)
        !config.hasValidBaseUrl -> AppError.Invalid(INVALID_SERVER_ADDRESS)
        else -> null
    }

    private fun Request.Builder.authorized(config: RetouchServerConfig): Request.Builder =
        if (config.token.isBlank()) this else header("Authorization", "Bearer ${config.token}")

    private fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun partName(disposition: String?): String? =
        disposition?.let { NAME_PATTERN.find(it)?.groupValues?.get(1) }

    /** null when [source] holds more than [limit] bytes: nothing larger is kept in memory. */
    private fun readBounded(source: okio.BufferedSource, limit: Long): ByteArray? {
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            if (buffer.size > limit) return null
        }
        return buffer.readByteArray()
    }

    internal companion object {
        const val NO_SERVER_ADDRESS = "no server address"
        const val INVALID_SERVER_ADDRESS = "invalid server address"
        const val NOT_A_RETOUCH_SERVER = "no skin retouch service at this address"
        const val UNSUPPORTED_CONTRACT = "unsupported contract version"
        const val INVALID_RESPONSE = "invalid server response"

        const val CONTRACT_VERSION = 1
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 16_777_216L
        const val MAX_BODY_BYTES = 90L * 1024 * 1024
        const val HEALTH_TIMEOUT_S = 5L
        const val CONNECT_TIMEOUT_S = 10L
        const val CALL_TIMEOUT_S = 60L

        const val PART_METADATA = "metadata"
        const val PART_IMAGE = "image"
        const val PART_ALLOWED = "allowed_mask"
        const val PART_CANDIDATE = "candidate"
        const val PART_SUPPORT = "change_support"
        const val OUTCOME_CORRECTED = "corrected"
        const val OUTCOME_NO_CHANGE = "no_change"

        private const val TAG = "RetouchServerClient"
        private const val HEALTH_PATH = "/health"
        private const val RETOUCH_PATH = "/v1/retouch"
        private const val STATUS_READY = "ready"
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val PNG_TYPE = "image/png"
        private const val ERROR_BODY_BYTES = 64L * 1024
        private const val READ_CHUNK_BYTES = 256L * 1024
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNPROCESSABLE = 422
        private const val RGBA_CHANNELS = 4
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE_MASK = 0xFF
        private const val ALPHA_MASK = -0x1000000
        private val ON = 255.toByte()
        private const val OFF: Byte = 0
        private val NAME_PATTERN = Regex("""name="([^"]*)"""")
    }
}
