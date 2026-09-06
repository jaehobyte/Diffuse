package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * specs/generative_erase.md §5, §6. One endpoint, one request shape: the whitened image plus an
 * instruction, answered with an image.
 *
 * The key travels in the `x-goog-api-key` header and never in the URL — URLs end up in logs,
 * crash reports and recorded requests, and headers are easier to keep out of all three.
 */
internal class GeminiEraseClient(
    private val configSource: GeminiConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient = OkHttpClient(),
    private val logger: Logger? = null,
) {

    sealed interface Outcome {
        /** The decoded bytes of the first `inlineData` part; PNG or JPEG, the decoder tells. */
        data class Success(val image: ByteArray) : Outcome {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Success && image.contentEquals(other.image))

            override fun hashCode(): Int = image.contentHashCode()
        }

        data class Failure(val error: AppError) : Outcome
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Generation is slow, and this is the one call where a long read is expected (§5). */
    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * @param jpeg the whitened image, already downscaled and compressed by the caller.
     * @param hint an optional phrase naming what used to be under the white region.
     */
    suspend fun erase(jpeg: ByteArray, hint: String?): Outcome = withContext(dispatchers.io) {
        val config = configSource.current()
        if (!config.isConfigured) {
            return@withContext Outcome.Failure(AppError.Invalid("no api key"))
        }
        coroutineContext.ensureActive()

        val payload = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(jpeg))),
                        Part(text = instruction(hint)),
                    ),
                ),
            ),
            generationConfig = GenerationConfig(responseModalities = listOf(IMAGE_MODALITY)),
        )
        val request = Request.Builder()
            .url("${config.baseUrl}$PATH")
            .header(API_KEY_HEADER, config.apiKey)
            .post(
                json.encodeToString(GenerateContentRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()

        try {
            http.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) read(body) else statusFailure(response.code, body)
            }
        } catch (e: IOException) {
            logger?.warn(TAG, "POST ${request.url.encodedPath} unreachable", e)
            Outcome.Failure(AppError.Io(e))
        }
    }

    /**
     * §5: the first part carrying `inlineData` wins. Text parts are ignored rather than treated
     * as an error — the model is allowed to narrate.
     */
    private fun read(body: String): Outcome {
        val response = try {
            json.decodeFromString(GenerateContentResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Outcome.Failure(AppError.Io(e))
        }
        val blocked = blockReason(response)
        val data = response.candidates.asSequence()
            .flatMap { it.content?.parts.orEmpty() }
            .mapNotNull { it.inlineData }
            .firstOrNull()
            ?.data
        return when {
            blocked != null -> {
                logger?.warn(TAG, "generation blocked: $blocked")
                Outcome.Failure(AppError.Invalid("$BLOCKED_PREFIX$blocked"))
            }
            data == null -> Outcome.Failure(AppError.Unsupported)
            else -> decode(data)
        }
    }

    private fun decode(data: String): Outcome = try {
        Outcome.Success(Base64.decode(data, Base64.DEFAULT))
    } catch (e: IllegalArgumentException) {
        Outcome.Failure(AppError.Io(e))
    }

    /** A refused prompt and a cut-short candidate are the same thing to the caller (§6). */
    private fun blockReason(response: GenerateContentResponse): String? =
        response.promptFeedback?.blockReason
            ?: response.candidates.firstOrNull()?.finishReason?.takeIf { it in BLOCKING_REASONS }

    /** specs/generative_erase.md §6, verbatim. No new `AppError` case. */
    private fun statusFailure(code: Int, body: String): Outcome {
        val error = errorOf(body)
        logger?.warn(TAG, "POST $PATH -> $code ${error.status}: ${error.message}")
        return Outcome.Failure(
            when {
                code == HTTP_BAD_REQUEST && error.status == FAILED_PRECONDITION -> AppError.Unavailable
                code == HTTP_BAD_REQUEST -> AppError.Invalid(error.message)
                code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> AppError.Unauthorized
                code == HTTP_NOT_FOUND -> AppError.Unsupported
                code == HTTP_PAYLOAD_TOO_LARGE -> AppError.TooLarge
                code == HTTP_TOO_MANY_REQUESTS -> AppError.Unavailable
                code in SERVER_ERRORS -> AppError.Unavailable
                else -> AppError.Io(IOException("HTTP $code: ${error.message}"))
            },
        )
    }

    private fun errorOf(body: String): GeminiError = try {
        json.decodeFromString(GeminiErrorEnvelope.serializer(), body).error
    } catch (_: SerializationException) {
        GeminiError(message = body)
    }

    private fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        })
    }

    companion object {
        /** Recognisable to the tool, which shows a different string for it (§9). */
        const val BLOCKED_PREFIX = "blocked:"

        const val API_KEY_HEADER = "x-goog-api-key"
        const val PATH = "/v1beta/models/gemini-2.5-flash-image:generateContent"

        /**
         * §5, verbatim. Wire payload, not user-facing copy, so DESIGN.md §9's "Korean, in
         * strings.xml" does not apply: English is where the model's instruction following is
         * most reliable.
         */
        const val INSTRUCTION =
            "The image contains a solid pure-white region. Replace that region with " +
                "photorealistic content that continues the surrounding scene: match its " +
                "lighting, texture, perspective, focus and grain so the result looks like a " +
                "single unedited photograph. Do not introduce any new object, person, text or " +
                "watermark. Do not alter anything outside the white region. Return only the " +
                "edited image."

        fun instruction(hint: String?): String =
            if (hint.isNullOrBlank()) {
                INSTRUCTION
            } else {
                "$INSTRUCTION The white region previously contained: $hint."
            }

        private const val TAG = "GeminiEraseClient"
        private const val JPEG_MEDIA_TYPE = "image/jpeg"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val IMAGE_MODALITY = "IMAGE"
        private const val FAILED_PRECONDITION = "FAILED_PRECONDITION"

        private val BLOCKING_REASONS =
            setOf("SAFETY", "PROHIBITED_CONTENT", "IMAGE_SAFETY")

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 60L

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = setOf(500, 503, 504)
    }
}
