package com.diffuse.core.ai.gemini

import android.util.Base64
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal const val FN_MATCH_STYLE = "match_style"

/**
 * specs/style_match.md §5's instruction, verbatim. English `internal` constant, per
 * generative_erase.md §5's rule: it is wire payload, not a string a person reads.
 */
internal const val MATCH_STYLE_INSTRUCTION =
    "You are given two photographs. The first is the photo being edited. The second is a " +
        "reference whose look the user wants to copy. Return only the colour and tone " +
        "adjustments that would move the first photograph towards the reference's look: its " +
        "exposure, contrast, tonal distribution, white balance, saturation and per-colour " +
        "treatment. Do not describe the reference's subject, composition or content, and do not " +
        "suggest adding or removing anything: the user wants the reference's grade, not its " +
        "picture. Values are scaled between -100 and +100."

/**
 * §5: tone and colour, and nothing else. `plannableKinds` would have added `sharpen` and
 * `vignette`, which are neither — the instruction asks for a grade, and a grade has no vignette.
 */
internal val matchStyleKinds: List<AdjustKind> = listOf(
    AdjustKind.Exposure,
    AdjustKind.Contrast,
    AdjustKind.Highlights,
    AdjustKind.Shadows,
    AdjustKind.Temperature,
    AdjustKind.Tint,
    AdjustKind.Saturation,
    AdjustKind.Vibrance,
)

internal val MATCH_STYLE_FUNCTION = FunctionDeclaration(
    name = FN_MATCH_STYLE,
    description = "Report the colour and tone adjustments that move the first photograph " +
        "towards the reference's look. Omit an adjustment that should not change.",
    parameters = Schema(
        type = "OBJECT",
        properties = matchStyleKinds.associate { kind ->
            kind.wireName to Schema(
                type = "NUMBER",
                description = "${kind.wireName}, from -100 to +100. 0 means leave it alone.",
            )
        },
    ),
)

/**
 * specs/style_match.md §5 step 2. Two photographs in, **numbers** out — never a graded picture,
 * for §2's reason: an image the model returns is one op nobody can then disagree with.
 *
 * The model is `gemini-2.5-flash`, the planner's, and not `-image`: this call produces a decision.
 */
internal class GeminiMatchStyleClient(
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

    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** [image] first and [reference] second, in the order the instruction names them. */
    suspend fun match(image: ByteArray, reference: ByteArray): Result<Map<AdjustKind, Float>> =
        withContext(dispatchers.io) {
            val config = configSource.current()
            if (!config.isConfigured) {
                return@withContext Result.Failure(AppError.Invalid("no api key"))
            }
            coroutineContext.ensureActive()

            val payload = GeneratePlanRequest(
                systemInstruction = Content(
                    parts = listOf(Part(text = MATCH_STYLE_INSTRUCTION)),
                ),
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(
                            Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(image))),
                            Part(inlineData = InlineData(JPEG_MEDIA_TYPE, base64(reference))),
                        ),
                    ),
                ),
                tools = listOf(Tool(functionDeclarations = listOf(MATCH_STYLE_FUNCTION))),
                toolConfig = ToolConfig(FunctionCallingConfig(mode = ANY_MODE)),
            )
            val httpRequest = Request.Builder()
                .url("${config.baseUrl}${GeminiPlanClient.PATH}")
                .header(GEMINI_API_KEY_HEADER, config.apiKey)
                .post(
                    json.encodeToString(GeneratePlanRequest.serializer(), payload)
                        .toRequestBody(GEMINI_JSON_MEDIA_TYPE.toMediaType()),
                )
                .build()

            try {
                http.newCall(httpRequest).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        read(body)
                    } else {
                        Result.Failure(geminiStatusError(response.code, geminiErrorOf(json, body)))
                    }
                }
            } catch (e: IOException) {
                logger?.warn(TAG, "POST unreachable", e)
                Result.Failure(AppError.Io(e))
            }
        }

    /**
     * §5: a model that answers in sentences has answered wrongly, and the client drops it — an
     * empty answer is a failure the sheet can name, not an empty style silently applied.
     */
    private fun read(body: String): Result<Map<AdjustKind, Float>> {
        val response = try {
            json.decodeFromString(GenerateContentResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Result.Failure(AppError.Io(e))
        }
        val call = response.candidates.firstOrNull()?.content?.parts.orEmpty()
            .firstNotNullOfOrNull { it.functionCall?.takeIf { fn -> fn.name == FN_MATCH_STYLE } }

        val adjustments = call?.let(::adjustmentsOf).orEmpty()
        return if (adjustments.isEmpty()) {
            Result.Failure(AppError.Invalid("no adjustments"))
        } else {
            Result.Success(adjustments)
        }
    }

    private fun adjustmentsOf(call: FunctionCall): Map<AdjustKind, Float> =
        matchStyleKinds.mapNotNull { kind ->
            val raw = (call.args[kind.wireName] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
                ?.floatOrNull
                ?.takeIf { it.isFinite() }
                ?: return@mapNotNull null
            // §5: clamped per kind, then divided by 100 into `Operation.Adjust`'s own range.
            val value = kind.coerce(raw.coerceIn(-FULL_SCALE, FULL_SCALE) / FULL_SCALE)
            if (kind.isNeutral(value)) null else kind to value
        }.toMap()

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val TAG = "GeminiMatchStyleClient"
        const val JPEG_MEDIA_TYPE = "image/jpeg"
        const val ANY_MODE = "ANY"
        const val FULL_SCALE = 100f
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 30L
    }
}
