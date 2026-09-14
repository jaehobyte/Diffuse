package com.diffuse.core.ai.monet

import android.graphics.Bitmap
import android.util.Base64
import com.diffuse.core.ai.AutoEnhanceProvider
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.gemini.await
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/auto_enhance.md §4. One endpoint, one request shape: the photograph plus an instruction,
 * answered with **numbers**.
 *
 * ADR-015: MonetGPT's own pipeline ends in GIMP and this client does not use that half. The
 * server runs the model; the device renders the plan. What comes back is a map of `AdjustKind` to
 * a value the renderer already knows how to apply at export resolution — never pixels.
 */
internal class MonetClient(
    private val configSource: MonetConfigSource,
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient = OkHttpClient(),
    private val logger: Logger? = null,
) {

    /** The adjustments, already in −1..1, and the model's own English reason for them. */
    data class Plan(val adjustments: Map<AdjustKind, Float>, val reason: String)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** §4: two MLLM turns on a photograph is slower than one generation, and that is expected. */
    private val http: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * §4: a short, finite probe of its own. The 120 s read above is for a generation; a server
     * that has not answered `/health` in a few seconds is not one the user should wait on.
     */
    private val probeHttp: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /**
     * §4: availability is a probe, **not** Gemini's "is a key present" rule. This server is the
     * user's own, so reachability is the real question and it is free to ask.
     *
     * §6: the answer keeps its reason, because each one sends the user somewhere different — a
     * blank or malformed address and a rejected token to the 서버 설정 sheet, a `503` (the model
     * is still loading) to "try again shortly", a refused or timed-out connection to a re-check.
     * [config] is the one being probed, so a late answer can be matched to the settings it was for.
     */
    suspend fun health(config: MonetConfig = configSource.current()): Result<Unit> =
        withContext(dispatchers.io) {
            addressError(config)?.let { return@withContext Result.Failure(it) }
            try {
                probeHttp.newCall(get(config, HEALTH_PATH)).await().use { response ->
                    when {
                        response.isSuccessful -> Result.Success(Unit)
                        // Something answered, and it is not this service: a wrong address.
                        response.code == HTTP_NOT_FOUND ->
                            Result.Failure(AppError.Invalid(NOT_A_MONET_SERVER))
                        else -> Result.Failure(
                            monetStatusError(response.code, response.body?.string().orEmpty()),
                        )
                    }
                }
            } catch (e: IOException) {
                logger?.warn(TAG, "GET $HEALTH_PATH unreachable", e)
                Result.Failure(AppError.Io(e))
            }
        }

    suspend fun plan(image: Bitmap, style: AutoStyle): Result<Plan> = withContext(dispatchers.io) {
        val config = configSource.current()
        addressError(config)?.let { return@withContext Result.Failure(it) }
        val encoded = encode(image) ?: return@withContext Result.Failure(AppError.TooLarge)
        coroutineContext.ensureActive()

        val request = post(config, encoded, style)
        try {
            http.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) read(body) else statusFailure(response.code, body)
            }
        } catch (e: IOException) {
            logger?.warn(TAG, "POST $CHAT_PATH unreachable", e)
            Result.Failure(AppError.Io(e))
        }
    }

    /** Checked before a request is built: `Request.Builder.url` throws on a malformed address. */
    private fun addressError(config: MonetConfig): AppError? = when {
        !config.isConfigured -> AppError.Invalid(NO_SERVER_ADDRESS)
        !config.hasValidBaseUrl -> AppError.Invalid(INVALID_SERVER_ADDRESS)
        else -> null
    }

    private fun get(config: MonetConfig, path: String): Request =
        Request.Builder().url("${config.baseUrl}$path").get().authorized(config).build()

    private fun post(config: MonetConfig, image: String, style: AutoStyle): Request {
        val payload = ChatRequest(
            model = MODEL,
            temperature = TEMPERATURE,
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = listOf(
                        ChatContent(type = "image_url", image_url = ChatImageUrl(image)),
                        ChatContent(
                            type = "text",
                            text = monetInstruction(style, MONET_OPERATIONS.keys.toList()),
                        ),
                    ),
                ),
            ),
        )
        return Request.Builder()
            .url("${config.baseUrl}$CHAT_PATH")
            .authorized(config)
            .post(
                json.encodeToString(ChatRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()
    }

    /** A blank token means no header at all: a self-hosted server may well want none. */
    private fun Request.Builder.authorized(config: MonetConfig): Request.Builder =
        if (config.token.isBlank()) this else header("Authorization", "Bearer ${config.token}")

    /** §4: 1280 on the long edge, PNG — `inference_config.yaml`'s own `max_dimension`. */
    private fun encode(image: Bitmap): String? {
        val scaled = downscale(image)
        val bytes = ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            .toByteArray()
        if (scaled !== image) scaled.recycle()
        if (bytes.size > MAX_UPLOAD_BYTES) return null
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun downscale(image: Bitmap): Bitmap {
        val longEdge = maxOf(image.width, image.height)
        if (longEdge <= MAX_LONG_EDGE) return image
        val ratio = MAX_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            image,
            (image.width * ratio).toInt().coerceAtLeast(1),
            (image.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * §4: "a reasoning model narrates", so the JSON is found inside the prose rather than being
     * the whole body. The text before it is the model's own reason, which §6 shows to the user.
     */
    private fun read(body: String): Result<Plan> {
        val reply = try {
            json.decodeFromString(ChatResponse.serializer(), body)
                .choices.firstOrNull()?.message?.content.orEmpty()
        } catch (e: SerializationException) {
            return Result.Failure(AppError.Io(e))
        }
        return planOf(reply) ?: Result.Failure(AppError.Unsupported)
    }

    /** null when the prose held no JSON object, or none of it named an operation we know. */
    private fun planOf(reply: String): Result<Plan>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) {
            logger?.warn(TAG, "no JSON object in the answer")
            return null
        }
        return adjustmentsOf(reply.substring(start, end + 1))
            ?.let { Result.Success(Plan(it, reply.take(start).trim())) }
    }

    /**
     * §4's own error row: the JSON parses but names no operation we recognise. An operation we do
     * not know drops **that** entry and keeps the rest — one unfamiliar name costs the user one
     * slider, not the whole boost.
     */
    private fun adjustmentsOf(text: String): Map<AdjustKind, Float>? {
        val parsed = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            logger?.warn(TAG, "unparseable JSON in the answer", e)
            null
        } ?: return null

        val adjustments = buildMap {
            parsed.forEach { (name, element) ->
                val kind = MONET_OPERATIONS[name]
                val value = element.jsonPrimitive.floatOrNull
                if (kind == null || value == null) {
                    logger?.warn(TAG, "dropped unknown adjustment '$name'")
                } else {
                    put(kind, (value / MONET_VALUE_SCALE).coerceIn(kind.range))
                }
            }
        }
        return adjustments.ifEmpty { null }
    }

    /** specs/generative_erase.md §6, reused rather than copied — §4 says "row for row". */
    private fun statusFailure(code: Int, body: String): Result<Plan> {
        logger?.warn(TAG, "POST $CHAT_PATH -> $code: ${body.take(ERROR_LOG_CHARS)}")
        return Result.Failure(monetStatusError(code, body))
    }

    internal companion object {
        /** `AppError.Invalid` details the tool tells apart; see `MonetAutoEnhanceProvider`. */
        const val NO_SERVER_ADDRESS = AutoEnhanceProvider.NO_SERVER
        const val INVALID_SERVER_ADDRESS = "invalid server address"
        const val NOT_A_MONET_SERVER = "no MonetGPT service at this address"

        private const val TAG = "MonetClient"
        private const val CHAT_PATH = "/v1/chat/completions"
        private const val HEALTH_PATH = "/health"
        private const val HTTP_NOT_FOUND = 404

        /** `configs/inference_config.yaml`'s own values. */
        private const val MODEL = "test"
        private const val TEMPERATURE = 0.3f
        private const val MAX_LONG_EDGE = 1280

        /** §4: generation is slow and this is the one call where a long read is expected. */
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 120L

        /** Connect, read and the whole call: a probe never outlives this. */
        private const val HEALTH_TIMEOUT_S = 5L

        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val PNG_QUALITY = 100
        private const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
        private const val ERROR_LOG_CHARS = 200
    }
}
