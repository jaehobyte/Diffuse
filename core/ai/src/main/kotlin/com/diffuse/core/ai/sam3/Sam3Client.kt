package com.diffuse.core.ai.sam3

import android.graphics.Bitmap
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class Sam3Session(
    val imageId: String,
    val width: Int,
    val height: Int,
    val expiresAtEpochMs: Long,
)

/** A mask straight off the wire: `ALPHA_8` at the **uploaded** image's size. */
internal data class RawMask(val alpha: Bitmap, val score: Float)

/**
 * specs/segmentation.md §2. Five endpoints, so no HTTP framework — OkHttp plus
 * kotlinx.serialization is the whole stack (architecture.md §2).
 */
internal class Sam3Client(
    private val configSource: Sam3ConfigSource,
    private val dispatchers: DispatcherProvider,
    private val okHttp: OkHttpClient = defaultOkHttp(),
    /**
     * architecture.md §9. The first device run had to be debugged from the *server's* access
     * log, because a failed call surfaced as a Korean snackbar and nothing else.
     */
    private val logger: Logger? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Shares the connection pool and dispatcher; only the read timeout differs. */
    private val uploadHttp: OkHttpClient by lazy {
        okHttp.newBuilder().readTimeout(UPLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    /** The one unauthenticated route. */
    suspend fun health(): Sam3Outcome<Unit> = request(
        client = okHttp,
        build = { config -> Request.Builder().url("${config.baseUrl}/healthz").get().build() },
        authenticated = false,
    ) { }

    suspend fun upload(image: ByteArray, mediaType: String): Sam3Outcome<Sam3Session> = request(
        client = uploadHttp,
        build = { config ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    if (mediaType == PNG_MEDIA_TYPE) "image.png" else "image.jpg",
                    image.toRequestBody(mediaType.toMediaType()),
                )
                .build()
            Request.Builder().url("${config.baseUrl}/v1/images").post(body).build()
        },
    ) { body ->
        val dto = json.decodeFromString(UploadDto.serializer(), body)
        Sam3Session(dto.imageId, dto.width, dto.height, parseExpiry(dto.expiresAt))
    }

    suspend fun points(imageId: String, prompt: PointPrompt): Sam3Outcome<List<RawMask>> {
        val payload = PointsRequestDto(
            points = prompt.points.map { listOf(it.x, it.y) },
            labels = prompt.labels.map { if (it) 1 else 0 },
            multimask = true,
            format = PNG_FORMAT,
        )
        return postJson(
            path = "/v1/images/$imageId/segment/points",
            body = json.encodeToString(PointsRequestDto.serializer(), payload),
        )
    }

    suspend fun text(imageId: String, phrase: String): Sam3Outcome<List<RawMask>> {
        val payload = TextRequestDto(
            prompt = phrase,
            threshold = TEXT_THRESHOLD,
            maxInstances = TEXT_MAX_INSTANCES,
            format = PNG_FORMAT,
        )
        return postJson(
            path = "/v1/images/$imageId/segment/text",
            body = json.encodeToString(TextRequestDto.serializer(), payload),
        )
    }

    /** Best effort, per specs/ai_provider.md §4: a lost release only costs the server a TTL. */
    suspend fun delete(imageId: String) {
        request(
            client = okHttp,
            build = { config ->
                Request.Builder().url("${config.baseUrl}/v1/images/$imageId").delete().build()
            },
        ) { }
    }

    private suspend fun postJson(path: String, body: String): Sam3Outcome<List<RawMask>> = request(
        client = okHttp,
        build = { config ->
            Request.Builder()
                .url("${config.baseUrl}$path")
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()
        },
    ) { responseBody ->
        json.decodeFromString(MasksDto.serializer(), responseBody).masks.mapNotNull { mask ->
            mask.png?.let { RawMask(MaskCodec.decode(it), mask.score) }
        }
    }

    private suspend fun <T> request(
        client: OkHttpClient,
        build: (Sam3Config) -> Request,
        authenticated: Boolean = true,
        parse: (String) -> T,
    ): Sam3Outcome<T> = withContext(dispatchers.io) {
        val config = configSource.current()
        if (!config.isConfigured) {
            return@withContext Sam3Outcome.Failure(AppError.Invalid("no base URL configured"))
        }
        coroutineContext.ensureActive()

        val request = build(config).let { built ->
            if (authenticated) {
                built.newBuilder().header("Authorization", "Bearer ${config.token}").build()
            } else {
                built
            }
        }

        try {
            client.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Sam3Outcome.Success(parse(body))
                } else {
                    logger?.warn(
                        TAG,
                        "${request.method} ${request.url.encodedPath} -> " +
                            "${response.code}: ${detailOf(body)}",
                    )
                    statusFailure(response.code, body)
                }
            }
        } catch (e: IOException) {
            // The one a misconfigured tunnel produces, and the one that used to be invisible.
            logger?.warn(TAG, "${request.method} ${request.url} unreachable", e)
            Sam3Outcome.Failure(AppError.Io(e))
        } catch (e: IllegalStateException) {
            // A malformed body, an undecodable mask PNG, or an unparseable timestamp.
            Sam3Outcome.Failure(AppError.Io(e))
        } catch (e: kotlinx.serialization.SerializationException) {
            Sam3Outcome.Failure(AppError.Io(e))
        }
    }

    /** specs/segmentation.md §4. */
    private fun statusFailure(code: Int, body: String): Sam3Outcome<Nothing> = when (code) {
        HTTP_BAD_REQUEST -> Sam3Outcome.Failure(AppError.Invalid(detailOf(body)))
        HTTP_UNAUTHORIZED -> Sam3Outcome.Failure(AppError.Unauthorized)
        HTTP_GONE -> Sam3Outcome.SessionExpired
        HTTP_PAYLOAD_TOO_LARGE -> Sam3Outcome.Failure(AppError.TooLarge)
        HTTP_UNSUPPORTED_MEDIA_TYPE -> Sam3Outcome.Failure(AppError.Unsupported)
        // 429 carries Retry-After, but the server refuses rather than queueing and so do we.
        HTTP_TOO_MANY_REQUESTS, HTTP_UNAVAILABLE -> Sam3Outcome.Failure(AppError.Unavailable)
        else -> Sam3Outcome.Failure(AppError.Io(IOException("HTTP $code: ${detailOf(body)}")))
    }

    /** `detail` is for logs only; user-facing strings are Korean and live in strings.xml. */
    private fun detailOf(body: String): String = try {
        json.decodeFromString(ErrorDto.serializer(), body).detail.ifBlank { body }
    } catch (_: kotlinx.serialization.SerializationException) {
        body
    }

    private fun parseExpiry(value: String): Long = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // Advisory only (specs/ai_provider.md §3); an unreadable timestamp is not a failure.
        Long.MAX_VALUE
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }

    companion object {
        private const val TAG = "Sam3Client"

        const val PNG_MEDIA_TYPE = "image/png"
        const val JPEG_MEDIA_TYPE = "image/jpeg"

        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val PNG_FORMAT = "png"
        private const val TEXT_THRESHOLD = 0.5f
        private const val TEXT_MAX_INSTANCES = 20

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L
        private const val UPLOAD_READ_TIMEOUT_S = 60L

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_GONE = 410
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_UNAVAILABLE = 503

        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
