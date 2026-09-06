package com.diffuse.core.ai.gemini

import com.diffuse.core.common.AppError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What `GeminiEraseClient` and `GeminiPlanClient` share: one host, one credential header, one
 * error envelope. specs/vibe_edit.md §6 says the planner maps errors "row for row" as the eraser
 * does, and a single implementation is how that stays true.
 */
internal const val GEMINI_API_KEY_HEADER = "x-goog-api-key"

internal const val GEMINI_JSON_MEDIA_TYPE = "application/json; charset=utf-8"

/** Recognisable to the tool, which shows a different string for it. */
internal const val GEMINI_BLOCKED_PREFIX = "blocked:"

/** specs/generative_erase.md §6, verbatim. No new `AppError` case. */
internal fun geminiStatusError(code: Int, error: GeminiError): AppError = when {
    code == HTTP_BAD_REQUEST && error.status == FAILED_PRECONDITION -> AppError.Unavailable
    code == HTTP_BAD_REQUEST -> AppError.Invalid(error.message)
    code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> AppError.Unauthorized
    code == HTTP_NOT_FOUND -> AppError.Unsupported
    code == HTTP_PAYLOAD_TOO_LARGE -> AppError.TooLarge
    code == HTTP_TOO_MANY_REQUESTS -> AppError.Unavailable
    code in SERVER_ERRORS -> AppError.Unavailable
    else -> AppError.Io(IOException("HTTP $code: ${error.message}"))
}

/** An error body that does not parse is still an error; its text becomes the message. */
internal fun geminiErrorOf(json: Json, body: String): GeminiError = try {
    json.decodeFromString(GeminiErrorEnvelope.serializer(), body).error
} catch (_: SerializationException) {
    GeminiError(message = body)
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
    })
}

private const val FAILED_PRECONDITION = "FAILED_PRECONDITION"
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_TOO_MANY_REQUESTS = 429
private val SERVER_ERRORS = setOf(500, 503, 504)
