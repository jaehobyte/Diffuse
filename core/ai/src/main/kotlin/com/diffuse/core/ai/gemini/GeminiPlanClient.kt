package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.HslChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/vibe_edit.md §5, §6. One planning call: the photo, the sentence and the four functions
 * this editor has, answered with the calls to run.
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
     * @param request the user's sentence, sent verbatim.
     */
    suspend fun plan(jpeg: ByteArray, request: String): Result<EditPlan> =
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
                        parts = listOf(
                            Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(jpeg))),
                            Part(text = request),
                        ),
                    ),
                ),
                tools = listOf(Tool(functionDeclarations = PLAN_FUNCTIONS)),
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
                    if (response.isSuccessful) read(body) else statusFailure(response.code, body)
                }
            } catch (e: IOException) {
                logger?.warn(TAG, "POST $PATH unreachable", e)
                Result.Failure(AppError.Io(e))
            }
        }

    /**
     * §5: `candidates[0].content.parts` in order, every `functionCall` kept and every text part
     * skipped. Zero function calls is an empty plan, not an error (§6).
     */
    private fun read(body: String): Result<EditPlan> {
        val response = try {
            json.decodeFromString(GenerateContentResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Result.Failure(AppError.Io(e))
        }
        val blocked = blockReason(response)
        return if (blocked != null) {
            logger?.warn(TAG, "planning blocked: $blocked")
            Result.Failure(AppError.Invalid("$GEMINI_BLOCKED_PREFIX$blocked"))
        } else {
            val steps = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                .mapNotNull { it.functionCall }
                .flatMap(::steps)
            // A plan that is accepted logs nothing otherwise, so "the tool only selected the
            // thing" cannot be told apart from "the run stopped at step 0" after the fact. The
            // failure T52 fought is invisible without this line. It logs the plan **after** §5's
            // reordering, because that is the order the run will actually take.
            val plan = cropLast(steps)
            logger?.debug(TAG, "plan (${plan.size}): ${plan.joinToString(" -> ")}")
            Result.Success(EditPlan(plan))
        }
    }

    /**
     * §5: an unknown name, a missing required argument or a non-finite value drops **that** step
     * and keeps the rest — the rule `EditDocumentJson` uses for an unknown operation.
     */
    private fun steps(call: FunctionCall): List<PlanStep> {
        val steps = when (call.name) {
            FN_SELECT_REGION -> listOfNotNull(
                call.args.string(ARG_PHRASE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(PlanStep::Select),
            )
            FN_ADJUST -> listOfNotNull(adjust(call.args))
            FN_ADJUST_COLOR_RANGE -> colorRange(call.args)
            FN_ERASE_SELECTION -> listOf(PlanStep.Erase)
            FN_CUT_OUT_SELECTION -> listOf(PlanStep.CutOut)
            FN_FILL_SELECTION -> listOfNotNull(
                call.args.string(ARG_PROMPT)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(PlanStep::Fill),
            )
            FN_CROP_RATIO -> listOfNotNull(
                call.args.string(ARG_RATIO)?.let(::cropRatioOf)?.let(PlanStep::Crop),
            )
            else -> emptyList()
        }
        if (steps.isEmpty()) logger?.warn(TAG, "dropped function call '${call.name}' ${call.args}")
        return steps
    }

    /**
     * specs/vibe_edit.md §5, §4.1: however many crops the model emitted and wherever it put them,
     * keep the **last** and move it to the **end**. Two crops in one plan is the model restating
     * itself rather than asking to crop twice, and `Operation.Crop` is at-most-one anyway
     * (edit_model.md). No other step is reordered — the model's order is the plan everywhere else.
     */
    private fun cropLast(steps: List<PlanStep>): List<PlanStep> {
        val crop = steps.lastOrNull { it is PlanStep.Crop } ?: return steps
        return steps.filterNot { it is PlanStep.Crop } + crop
    }

    private fun adjust(args: JsonObject): PlanStep.Adjust? {
        val kind = args.string(ARG_KIND)?.let(::adjustKindOf)
        val value = args.float(ARG_VALUE)?.takeIf { it.isFinite() }
        return if (kind == null || value == null) {
            null
        } else {
            PlanStep.Adjust(kind, kind.coerce(value), args.boolean(ARG_MASKED) ?: true)
        }
    }

    /**
     * specs/adjust_hsl.md §8: one call becomes up to three ordinary `Adjust` steps, in the fixed
     * order hue → saturation → luminance. The runner already knows how to apply an `Adjust`, so
     * 혼합 costs the plan model nothing.
     *
     * `masked` defaults to **false** here, unlike `adjust`: a colour range is chosen by colour
     * rather than by region, and a masked adjust with no selection is a plan `PlanRunner.validate`
     * rejects outright (specs/vibe_edit.md §9.1).
     */
    private fun colorRange(args: JsonObject): List<PlanStep> {
        val band = hslBandOf(args.string(ARG_COLOR).orEmpty()) ?: return emptyList()
        val masked = args.boolean(ARG_MASKED) ?: false
        return HslChannel.entries.mapNotNull { channel ->
            val value = args.float(channel.wireName)
                ?.takeIf { it.isFinite() && it != 0f }
                ?: return@mapNotNull null
            val kind = hslKindOf(band, channel)
            PlanStep.Adjust(kind, kind.coerce(value), masked)
        }
    }

    /** A refused prompt and a cut-short candidate are the same thing to the caller (§6). */
    private fun blockReason(response: GenerateContentResponse): String? =
        response.promptFeedback?.blockReason
            ?: response.candidates.firstOrNull()?.finishReason?.takeIf { it in BLOCKING_REASONS }

    private fun statusFailure(code: Int, body: String): Result<EditPlan> {
        val error = geminiErrorOf(json, body)
        logger?.warn(TAG, "POST $PATH -> $code ${error.status}: ${error.message}")
        return Result.Failure(geminiStatusError(code, error))
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.content

    private fun JsonObject.float(key: String): Float? = primitive(key)?.floatOrNull

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.booleanOrNull

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        /** The text model. `-image` neither declares functions nor benefits from being asked to. */
        const val PATH = "/v1beta/models/gemini-2.5-flash:generateContent"

        private const val TAG = "GeminiPlanClient"
        private const val JPEG_MEDIA_TYPE = "image/jpeg"

        /** Forces function calls over prose: narration is nothing the app can run (§5). */
        private const val ANY_MODE = "ANY"

        private val BLOCKING_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT")

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L
    }
}
