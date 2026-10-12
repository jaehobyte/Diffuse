package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/vibe_edit.md §14. One read-only call: the preview and the catalog, answered with one
 * `suggest_directions` call whose only argument is a list of catalog ids. Same model, host, header
 * and error mapping as [GeminiPlanClient]; nothing but the ids is read from the answer.
 */
internal class GeminiSuggestionClient(
    private val configSource: GeminiConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient = OkHttpClient(),
    private val logger: Logger? = null,
    /** §14: the whole request, from send to parsed ids. A seam only so a test need not wait 12 s. */
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** @param jpeg the preview, already downscaled and compressed by the caller. */
    suspend fun suggest(jpeg: ByteArray): Result<List<PromptSuggestionId>> =
        withContext(dispatchers.io) {
            val config = configSource.current()
            if (!config.isConfigured) {
                return@withContext Result.Failure(AppError.Invalid("no api key"))
            }
            coroutineContext.ensureActive()
            withTimeoutOrNull(timeoutMs) { post(config, jpeg) }
                ?: Result.Failure(AppError.Io(IOException("suggestion timed out")))
        }

    private suspend fun post(config: GeminiConfig, jpeg: ByteArray): Result<List<PromptSuggestionId>> {
        val payload = GeneratePlanRequest(
            systemInstruction = Content(parts = listOf(Part(text = SUGGESTION_SYSTEM_INSTRUCTION))),
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(jpeg))),
                        Part(text = SUGGESTION_CATALOG),
                    ),
                ),
            ),
            tools = listOf(Tool(functionDeclarations = listOf(SUGGEST_FUNCTION))),
            toolConfig = ToolConfig(FunctionCallingConfig(mode = ANY_MODE)),
        )
        val request = Request.Builder()
            .url("${config.baseUrl}${GeminiPlanClient.PATH}")
            .header(GEMINI_API_KEY_HEADER, config.apiKey)
            .post(
                json.encodeToString(GeneratePlanRequest.serializer(), payload)
                    .toRequestBody(GEMINI_JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()
        return try {
            http.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) read(body) else statusFailure(response.code, body)
            }
        } catch (e: IOException) {
            logger?.warn(TAG, "suggestion unreachable", e)
            Result.Failure(AppError.Io(e))
        }
    }

    /**
     * §14: the first `suggest_directions` call's `ids`, in order. A missing call, a missing or
     * non-list `ids`, a refusal and a cut-short answer are failures; unknown ids are dropped, and
     * an empty list after that is the valid "nothing fits".
     */
    private fun read(body: String): Result<List<PromptSuggestionId>> {
        val response = try {
            json.decodeFromString(GenerateContentResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Result.Failure(AppError.Io(e))
        }
        val refusal = refusalOf(response)
        val ids = idsOf(response)
        return when {
            refusal != null -> Result.Failure(refusal)
            ids == null -> Result.Failure(AppError.Io(IOException("no suggestion ids")))
            else -> {
                val shown = PromptSuggestionId.distinctDirections(ids)
                logger?.debug(TAG, "suggestions: ${shown.joinToString { it.wire }}")
                Result.Success(shown)
            }
        }
    }

    /** A refused prompt is `Invalid("blocked:…")`, as the planner's; any other non-STOP end is Io. */
    private fun refusalOf(response: GenerateContentResponse): AppError? {
        val finish = response.candidates.firstOrNull()?.finishReason
        val blocked = response.promptFeedback?.blockReason ?: finish?.takeIf { it in BLOCKING_REASONS }
        return when {
            blocked != null -> AppError.Invalid("$GEMINI_BLOCKED_PREFIX$blocked")
            finish != null && finish != FINISH_STOP -> AppError.Io(IOException("suggestion cut short: $finish"))
            else -> null
        }
    }

    /** The known ids of the first `suggest_directions` call, or null when there is no id list. */
    private fun idsOf(response: GenerateContentResponse): List<PromptSuggestionId>? {
        val ids = response.candidates.firstOrNull()?.content?.parts.orEmpty()
            .mapNotNull { it.functionCall }
            .firstOrNull { it.name == FN_SUGGEST_DIRECTIONS }
            ?.args?.get(ARG_IDS) as? JsonArray
        return ids?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.mapNotNull(PromptSuggestionId::ofWire)
    }

    private fun statusFailure(code: Int, body: String): Result<List<PromptSuggestionId>> {
        val error = geminiErrorOf(json, body)
        logger?.warn(TAG, "suggestion -> $code ${error.status}")
        return Result.Failure(geminiStatusError(code, error))
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        const val TIMEOUT_MS = 12_000L

        private const val TAG = "GeminiSuggestionClient"
        private const val JPEG_MEDIA_TYPE = "image/jpeg"
        private const val ANY_MODE = "ANY"
        private const val FINISH_STOP = "STOP"
        private val BLOCKING_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT")
    }
}
