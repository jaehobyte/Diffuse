package com.diffuse.core.ai.erase

import com.diffuse.core.ai.sam3.Sam3Client
import com.diffuse.core.ai.sam3.Sam3ConfigSource
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * specs/generative_erase.md §2. `POST /v1/edit/erase`, multipart: the image, the mask, and an
 * optional hint. There is no session, so no `410` path — unlike segmentation.
 */
@Singleton
internal class Sam3EraseClient @Inject constructor(
    private val configSource: Sam3ConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient,
) {

    sealed interface Outcome {
        data class Success(val png: ByteArray) : Outcome {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Success && png.contentEquals(other.png))

            override fun hashCode(): Int = png.contentHashCode()
        }

        data class Failure(val error: AppError) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Generation is slow, and this is the one call where a long read is expected. */
    private val http: OkHttpClient = okHttp.newBuilder()
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    suspend fun erase(image: ByteArray, mask: ByteArray, hint: String?): Outcome =
        withContext(dispatchers.io) {
            val config = configSource.current()
            if (!config.isConfigured) {
                return@withContext Outcome.Failure(AppError.Invalid("no base URL configured"))
            }
            coroutineContext.ensureActive()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "image.jpg",
                    image.toRequestBody(Sam3Client.JPEG_MEDIA_TYPE.toMediaType()),
                )
                .addFormDataPart(
                    "mask",
                    "mask.png",
                    mask.toRequestBody(Sam3Client.PNG_MEDIA_TYPE.toMediaType()),
                )
                .apply { hint?.takeIf { it.isNotBlank() }?.let { addFormDataPart("hint", it) } }
                .build()

            val request = Request.Builder()
                .url("${config.baseUrl}$PATH")
                .header("Authorization", "Bearer ${config.token}")
                .post(body)
                .build()

            try {
                http.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        Outcome.Success(response.body?.bytes() ?: ByteArray(0))
                    } else {
                        Outcome.Failure(statusError(response.code, response.body?.string()))
                    }
                }
            } catch (e: IOException) {
                Outcome.Failure(AppError.Io(e))
            }
        }

    /** The same table as specs/segmentation.md §4, minus the 410 a session-less call cannot get. */
    private fun statusError(code: Int, body: String?): AppError = when (code) {
        HTTP_BAD_REQUEST -> AppError.Invalid(detailOf(body))
        HTTP_UNAUTHORIZED -> AppError.Unauthorized
        HTTP_PAYLOAD_TOO_LARGE -> AppError.TooLarge
        HTTP_UNSUPPORTED_MEDIA_TYPE -> AppError.Unsupported
        HTTP_TOO_MANY_REQUESTS, HTTP_UNAVAILABLE -> AppError.Unavailable
        else -> AppError.Io(IOException("HTTP $code: ${detailOf(body)}"))
    }

    private fun detailOf(body: String?): String = try {
        body?.let { json.decodeFromString(ErrorBody.serializer(), it).detail }.orEmpty()
    } catch (_: kotlinx.serialization.SerializationException) {
        body.orEmpty()
    }

    @kotlinx.serialization.Serializable
    private data class ErrorBody(val error: String = "", val detail: String = "")

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        })
    }

    private companion object {
        const val PATH = "/v1/edit/erase"
        const val READ_TIMEOUT_S = 60L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAVAILABLE = 503
    }
}
