package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/vibe_edit.md §5, §6, §14. One planning call: the photo, its current settings, the mode
 * and the request, answered with one `propose_edit` envelope that carries the evaluation and the
 * workflow. 보정 추천 and a sent 지시 are this one call with a different mode.
 *
 * The model is `gemini-2.5-flash` and **not** `-image`: this call produces a decision, not pixels.
 * The key travels in the `x-goog-api-key` header for the reason generative_erase.md §5 gives.
 */
internal class GeminiPlanClient(
    private val configSource: GeminiConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient = OkHttpClient(),
    private val logger: Logger? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Half the eraser's read timeout: a 60 s wait for a text generation means something is wrong. */
    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * @param jpeg the preview, already downscaled and compressed by the caller.
     * @param request the user's text is sent verbatim, in its own part, when it is not blank.
     */
    suspend fun plan(jpeg: ByteArray, request: PlanRequest): Result<PlanProposal> =
        withContext(dispatchers.io) {
            val config = configSource.current()
            if (!config.isConfigured) {
                return@withContext Result.Failure(AppError.Invalid("no api key"))
            }
            coroutineContext.ensureActive()

            val payload = GeneratePlanRequest(
                systemInstruction = Content(parts = listOf(Part(text = PLAN_SYSTEM_INSTRUCTION))),
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOfNotNull(
                            Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(jpeg))),
                            Part(text = contextText(request)),
                            request.text.takeIf { it.isNotBlank() }?.let { Part(text = it) },
                        ),
                    ),
                ),
                tools = listOf(Tool(functionDeclarations = listOf(PROPOSE_FUNCTION))),
                toolConfig = ToolConfig(FunctionCallingConfig(mode = ANY_MODE)),
            )
            val httpRequest = Request.Builder()
                .url("${config.baseUrl}$PATH")
                .header(GEMINI_API_KEY_HEADER, config.apiKey)
                .post(
                    json.encodeToString(GeneratePlanRequest.serializer(), payload)
                        .toRequestBody(GEMINI_JSON_MEDIA_TYPE.toMediaType()),
                )
                .build()

            try {
                http.newCall(httpRequest).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) read(body, request) else statusFailure(response.code, body)
                }
            } catch (e: IOException) {
                logger?.warn(TAG, "POST $PATH unreachable", e)
                Result.Failure(AppError.Io(e))
            }
        }

    /**
     * §14: exactly one function call, and it is `propose_edit`; text parts are skipped. Anything
     * else — no call, two envelopes, another name, or an envelope [ProposalDecoder] or
     * [ProposalCheck] refuses — is `Invalid("unplannable:…")`, and nothing of it is kept.
     */
    // Undecodable, blocked and refused are three different answers, so three returns.
    @Suppress("ReturnCount")
    private fun read(body: String, request: PlanRequest): Result<PlanProposal> {
        val response = try {
            json.decodeFromString(GenerateContentResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Result.Failure(AppError.Io(e))
        }
        val blocked = blockReason(response)
        if (blocked != null) {
            logger?.warn(TAG, "planning blocked: $blocked")
            return Result.Failure(AppError.Invalid("$GEMINI_BLOCKED_PREFIX$blocked"))
        }
        val calls = response.candidates.firstOrNull()?.content?.parts.orEmpty().mapNotNull { it.functionCall }
        val call = calls.singleOrNull()?.takeIf { it.name == FN_PROPOSE_EDIT }
        return try {
            val decoded = ProposalDecoder.decode(call?.args ?: throw Unplannable("${calls.size} function calls"))
            ProposalCheck.violation(decoded, request.text)?.let { throw Unplannable(it) }
            val proposal = cropLast(decoded)
            // §14: the outcome and the step kinds only. Observations, goals and the user's words
            // stay out of the log.
            logger?.debug(
                TAG,
                "proposal ${proposal.outcome} (${proposal.workflow.size}): " +
                    proposal.workflow.joinToString(" -> ") { it.step::class.simpleName.orEmpty() },
            )
            Result.Success(proposal)
        } catch (e: Unplannable) {
            logger?.warn(TAG, "proposal refused: ${e.message}")
            Result.Failure(AppError.Invalid("$UNPLANNABLE_PREFIX${e.message}"))
        }
    }

    /**
     * specs/vibe_edit.md §5, §4.1: a crop wherever the model put it moves to the **end**, so the
     * 자르기 tool can open on it. `ProposalCheck` refuses a second one. No other step is
     * reordered — the model's order is the plan everywhere else.
     */
    private fun cropLast(proposal: PlanProposal): PlanProposal {
        val crop = proposal.workflow.lastOrNull { it.step is PlanStep.Crop } ?: return proposal
        return proposal.copy(workflow = proposal.workflow.filterNot { it.step is PlanStep.Crop } + crop)
    }

    /**
     * §14: the mode and the document's current settings, as the final values a new `adjust`
     * would replace. A pure summary; no file path, project id or EXIF is sent.
     */
    private fun contextText(request: PlanRequest): String = buildString {
        val context = request.context
        append("Mode: ").append(if (request.mode == PlanMode.Recommend) "recommend" else "direct").append('\n')
        append("Request: ").append(if (request.text.isBlank()) "(none)" else "the next part, verbatim").append('\n')
        append("Current settings (final values; an adjust replaces the value for the same kind and scope):")
        if (context.adjustments.isEmpty()) append(" none")
        context.adjustments.forEach { adjust ->
            append("\n- ").append(adjust.kind.settingName).append(" = ")
                .append(String.format(Locale.ROOT, "%.2f", adjust.value))
                .append(if (adjust.masked) " (on an existing selection)" else " (whole photo)")
        }
        append("\nExisting selection: ").append(if (context.hasSelection) "yes" else "none")
        append("\nCropped: ").append(if (context.cropped) "yes" else "no")
    }

    /** A 혼합 kind reads as `adjust_color_range`'s own words, so the model can repeat it back. */
    private val AdjustKind.settingName: String
        get() = hsl?.let { "${it.band.wireName} ${it.channel.wireName}" } ?: wireName

    /** A refused prompt and a cut-short candidate are the same thing to the caller (§6). */
    private fun blockReason(response: GenerateContentResponse): String? =
        response.promptFeedback?.blockReason
            ?: response.candidates.firstOrNull()?.finishReason?.takeIf { it in BLOCKING_REASONS }

    private fun statusFailure(code: Int, body: String): Result<PlanProposal> {
        val error = geminiErrorOf(json, body)
        logger?.warn(TAG, "POST $PATH -> $code ${error.status}: ${error.message}")
        return Result.Failure(geminiStatusError(code, error))
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        /** The text model. `-image` neither declares functions nor benefits from being asked to. */
        const val PATH = "/v1beta/models/gemini-2.5-flash:generateContent"

        private const val TAG = "GeminiPlanClient"
        private const val JPEG_MEDIA_TYPE = "image/jpeg"

        /**
         * §14: a refusal of the envelope. `feature:editor` matches it the way it matches
         * `blocked:` — the same event to the user as a plan it cannot run.
         */
        const val UNPLANNABLE_PREFIX = "unplannable:"

        /** Forces function calls over prose: narration is nothing the app can run (§5). */
        private const val ANY_MODE = "ANY"

        private val BLOCKING_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT")

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L
    }
}
