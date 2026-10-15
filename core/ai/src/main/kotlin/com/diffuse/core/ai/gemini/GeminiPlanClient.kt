package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.ai.CropNote
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.ai.TopazCapability
import com.diffuse.core.ai.topaz.TopazPurpose
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
 * The model is `gemini-3.8-flash` and **not** `-image`: this call produces a decision, not pixels.
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
            val args = call?.args ?: throw Unplannable("${calls.size} function calls")
            val decoded = ProposalDecoder.decode(args, request.context)
            ProposalCheck.violation(decoded, request.text, request.context)?.let { throw Unplannable(it) }
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
     * specs/vibe_edit.md §5, §4.1: a crop wherever the model put it moves to the end of the local
     * steps, so the 자르기 tool can open on it; §19: a Topaz step, which is a hand-off and never a
     * local step, goes after everything; outpaint.md §3: an expand goes first, because an
     * `Outpaint` is always the document's first operation. `ProposalCheck` refuses a second of
     * each. No other step is reordered — the model's order is the plan everywhere else — and
     * every step keeps its id.
     */
    private fun cropLast(proposal: PlanProposal): PlanProposal {
        val (expands, rest) = proposal.workflow.partition { it.step is PlanStep.Expand }
        val (moved, kept) = rest.partition { it.step is PlanStep.Crop || it.step is PlanStep.Topaz }
        if (expands.isEmpty() && moved.isEmpty()) return proposal
        return proposal.copy(workflow = expands + kept + moved.sortedBy { it.step is PlanStep.Topaz })
    }

    /**
     * §14, §15: the mode and the snapshot — every value as the final setting a new step would
     * replace, every selection by its opaque ref, the operation order, the crop, and what each
     * style and Topaz purpose actually does. A pure summary: no file path, mask id, project id,
     * key or EXIF is sent.
     */
    private fun contextText(request: PlanRequest): String = buildString {
        val context = request.context
        append("Mode: ").append(if (request.mode == PlanMode.Recommend) "recommend" else "direct").append('\n')
        append("Request: ").append(if (request.text.isBlank()) "(none)" else "the next part, verbatim").append('\n')
        append("Snapshot. The photo you see is already rendered with every operation below.\n")
        if (context.imageWidth > 0) append("Preview: ${context.imageWidth}x${context.imageHeight}\n")
        append("Crop: ").append(context.crop?.let(::cropText) ?: if (context.cropped) "yes" else "none").append('\n')
        settings(context)
        targets(context)
        operations(context)
        styles(context)
        context.topaz?.let { topaz(it) }
    }

    private fun StringBuilder.settings(context: PlanContext) {
        val whole = context.adjustments.filter { it.targetRef == null }
        append("Whole-photo settings (final values; a kind not listed is 0; an adjust replaces the value in place):")
        val tones = whole.filter { it.kind.hsl == null }
        if (tones.isEmpty()) append(" none")
        tones.forEach { append("\n- ").append(adjustText(it)) }
        append("\nColour ranges (whole photo; every band and channel not listed is 0):")
        val ranges = whole.filter { it.kind.hsl != null }
        if (ranges.isEmpty()) append(" all 0")
        ranges.forEach { append("\n- ").append(adjustText(it)) }
        append('\n')
    }

    private fun StringBuilder.targets(context: PlanContext) {
        append("Selections (targets):")
        if (context.targets.isEmpty()) append(" none")
        context.targets.forEach { target ->
            append("\n- ").append(target.ref).append(if (target.active) " (active)" else "").append(": ")
            val description = target.description
            if (description != null && target.source == TargetSource.Confirmed) {
                append("\"").append(description).append("\", confirmed by the user in this session")
            } else if (description != null && target.source == TargetSource.Resolved) {
                append("\"").append(description)
                    .append("\", found for that phrase by an earlier plan in this session (not confirmed by the user)")
            } else {
                append("unknown - made by hand or in an earlier session; do not guess what it is")
            }
            val own = context.adjustments.filter { it.targetRef == target.ref }
            append("; settings: ").append(if (own.isEmpty()) "none" else own.joinToString(", ") { adjustText(it) })
        }
        append('\n')
    }

    private fun StringBuilder.operations(context: PlanContext) {
        append("Operation order (rendered first to last, crop always last):")
        if (context.operations.isEmpty()) append(" none")
        context.operations.forEach { note ->
            append("\n").append(note.order).append(". ").append(note.kind)
            note.targetRef?.let { append(" on ").append(it) }
        }
        append('\n')
    }

    private fun StringBuilder.styles(context: PlanContext) {
        if (context.styles.isEmpty()) return
        append(
            "Styles (the values each writes at intensity 100; intensity multiplies them, a value that " +
                "becomes 0 is not written; a written value replaces that whole-photo setting in place, a " +
                "new one is appended; nothing else changes):",
        )
        context.styles.forEach { effect ->
            append("\n- ").append(effect.style.id).append(": ")
            append(effect.values.entries.joinToString(", ") { (kind, value) -> "${kind.settingName}=${format(value)}" })
        }
        append('\n')
    }

    private fun StringBuilder.topaz(capability: TopazCapability) {
        append("Topaz (paid, remote, whole photo; the result is a high-resolution file or a new edit copy): ")
        append(if (capability.configured) "key set" else "no key - the user must add one in settings first")
        val width = capability.inputWidth
        val height = capability.inputHeight
        append("; input ").append(if (width != null && height != null) "${width}x$height" else "size unknown")
        if (capability.hasAlpha) append("; the photo has transparency, so no purpose can run")
        TopazPurpose.entries.forEach { purpose ->
            append("\n- ").append(purpose.wireName).append(": scale ").append(purpose.scales.joinToString("/"))
            append(", face_recovery ").append(if (purpose.faceRecovery) "available" else "not available")
            if (purpose.manualSharpen) append(", sharpen_strength available")
            if (purpose.generative) append(", generative")
            append(", max ${purpose.maxInputMegapixels} MP in / ${purpose.maxOutputMegapixels} MP out")
        }
        append('\n')
    }

    private fun adjustText(adjust: CurrentAdjust): String =
        "${adjust.kind.settingName}=${format(adjust.value)}" +
            (if (adjust.ref.isNotEmpty()) " (${adjust.ref}, #${adjust.order})" else "")

    private fun cropText(crop: CropNote): String =
        String.format(
            Locale.ROOT,
            "kept %.2f..%.2f x %.2f..%.2f of the frame",
            crop.left,
            crop.right,
            crop.top,
            crop.bottom,
        ) +
            if (crop.angleDeg != 0f) String.format(Locale.ROOT, ", rotated %.1f deg", crop.angleDeg) else ""

    private fun format(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    /**
     * A 혼합 kind reads as `adjust_color_range`'s own words, so the model can repeat it back; a
     * kind `adjust` does not offer keeps its plain name, since a style may still write it.
     */
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
        const val PATH = "/v1beta/models/gemini-3.8-flash:generateContent"

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
