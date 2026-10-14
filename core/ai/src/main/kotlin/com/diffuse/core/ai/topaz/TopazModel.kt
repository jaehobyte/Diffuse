package com.diffuse.core.ai.topaz

import java.io.File
import kotlin.math.roundToInt

/**
 * specs/topaz_enhancement.md §2. The Image API routes a request by endpoint first and `model`
 * second; the estimate for a generative endpoint is a different route from a standard one.
 *
 * Paths and categories are the official OpenAPI document's (Image API 1.2.0, checked 2026-10-13).
 */
enum class TopazEndpoint(
    internal val submitPath: String,
    internal val estimatePath: String,
    internal val estimateCategory: String,
    /** Only the two enhance endpoints take `output_width` / `output_height`. */
    internal val takesOutputSize: Boolean,
) {
    Enhance("/enhance/async", "/estimate", "Enhance", takesOutputSize = true),
    EnhanceGenerative("/enhance-gen/async", "/estimate-gen", "Enhance", takesOutputSize = true),
    Sharpen("/sharpen/async", "/estimate", "Sharpen", takesOutputSize = false),
    Denoise("/denoise/async", "/estimate", "Denoise", takesOutputSize = false),
    RestoreGenerative("/restore-gen/async", "/estimate-gen", "Restore", takesOutputSize = false),
    Lighting("/lighting/async", "/estimate", "Lighting", takesOutputSize = false),
}

/**
 * specs/topaz_enhancement.md §2. One purpose is one request: a user-facing goal, the endpoint and
 * the wire `model` value that serves it, and what that model is documented to accept.
 *
 * The product label lives in `feature:editor`'s strings; [wireModel] is never shown.
 *
 * @param scales the output multipliers this purpose offers. `[1]` means the endpoint takes no
 *   output size and answers at the input's.
 * @param maxInputMegapixels / [maxOutputMegapixels] the model page's stated limits.
 * @param faceRecovery whether the model page documents `faceEnhancement`.
 * @param manualSharpen whether `sharpenStrength` is documented for this model, unambiguously.
 * @param generative a generative model, which may redraw shapes, text and faces (§3).
 */
// The enum is the capability table, and the numbers in it are the model pages' own limits.
@Suppress("LongParameterList", "MagicNumber")
enum class TopazPurpose(
    val endpoint: TopazEndpoint,
    internal val wireModel: String,
    val scales: List<Int>,
    val maxInputMegapixels: Int,
    val maxOutputMegapixels: Int,
    val faceRecovery: Boolean = false,
    val manualSharpen: Boolean = false,
    val generative: Boolean = false,
) {
    /** T1. Gigapixel Standard 2; the page's display name and the wire value differ. */
    Upscale(TopazEndpoint.Enhance, "Standard V2", listOf(2, 4), 512, 1024, faceRecovery = true),

    /** T1. The reference enum's `"Standard"`; the model page's `"Sharpen"` is not in it. */
    Sharpen(TopazEndpoint.Sharpen, "Standard", listOf(1), 512, 512, faceRecovery = true, manualSharpen = true),

    /** T1. Denoise Normal. Its page lists two strength fields, so only Autopilot is offered. */
    Denoise(TopazEndpoint.Denoise, "Normal", listOf(1), 512, 512, faceRecovery = true),

    /** T2. 사진 복원. */
    Restore(
        TopazEndpoint.EnhanceGenerative, "Wonder", listOf(1, 2, 4), 128, 128,
        faceRecovery = true, generative = true,
    ),

    /** T2. 창의적 디테일 — Redefine, which the reference enum and its model page both name. */
    Creative(
        TopazEndpoint.EnhanceGenerative, "Redefine", listOf(1, 2, 4), 256, 256,
        faceRecovery = true, generative = true,
    ),

    /** T3. Adjust Lighting 2. */
    Lighting(TopazEndpoint.Lighting, "Adjust V2", listOf(1), 512, 512),

    /** T3. Balance Color. */
    ColorBalance(TopazEndpoint.Lighting, "White Balance", listOf(1), 512, 512),

    /** T3. Image Colorization. */
    Colorize(TopazEndpoint.Lighting, "Colorize", listOf(1), 512, 512),

    /** T3. Dust & Scratch 2, a generative restore. */
    DustScratch(
        TopazEndpoint.RestoreGenerative, "Dust-Scratch V2", listOf(1), 256, 256, generative = true,
    ),
    ;

    val defaultScale: Int get() = scales.first()
}

/**
 * specs/topaz_enhancement.md §3. What the user chose. [sharpenStrength] null is Autopilot, and
 * Autopilot sends no strength at all.
 */
data class TopazOptions(
    val scale: Int,
    val faceRecovery: Boolean = false,
    val sharpenStrength: Float? = null,
) {
    companion object {
        fun defaultsFor(purpose: TopazPurpose) = TopazOptions(scale = purpose.defaultScale)
    }
}

/** The input is a JPEG of the rendered document; nothing else is sent (§5). */
data class TopazJob(
    val purpose: TopazPurpose,
    val options: TopazOptions,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputHasAlpha: Boolean = false,
) {

    val outputWidth: Int get() = inputWidth * options.scale

    /** What the API's proportional scaling gives for [outputWidth]; checked within ±1px. */
    val outputHeight: Int get() = (inputHeight.toLong() * outputWidth / inputWidth.toDouble()).roundToInt()

    /** The size the result comes back at: the output size where the endpoint takes one, else the input's. */
    val resultWidth: Int get() = if (purpose.endpoint.takesOutputSize) outputWidth else inputWidth
    val resultHeight: Int get() = if (purpose.endpoint.takesOutputSize) outputHeight else inputHeight

    /** Everything that makes this job unsendable, found before any request is made (§3). */
    fun problem(): TopazFailure? = when {
        inputWidth <= 0 || inputHeight <= 0 -> TopazFailure.InputUnsupported
        // No model the app sends to is documented to keep alpha; a silent white or black matte is
        // not something to pay for.
        inputHasAlpha -> TopazFailure.InputHasAlpha
        options.scale !in purpose.scales -> TopazFailure.OptionUnsupported
        options.faceRecovery && !purpose.faceRecovery -> TopazFailure.OptionUnsupported
        options.sharpenStrength != null &&
            (!purpose.manualSharpen || options.sharpenStrength !in 0f..1f) -> TopazFailure.OptionUnsupported
        megapixels(inputWidth, inputHeight) > purpose.maxInputMegapixels -> TopazFailure.LimitExceeded
        !fitsOutput(purpose, options.scale) -> TopazFailure.LimitExceeded
        else -> null
    }

    /** Whether [scale] keeps this input inside the model's and the app's output limits (§3). */
    fun fitsOutput(purpose: TopazPurpose, scale: Int): Boolean {
        val width = inputWidth.toLong() * scale
        val height = inputHeight.toLong() * scale
        val pixels = width * height
        return width <= MAX_SIDE_PX && height <= MAX_SIDE_PX &&
            pixels <= purpose.maxOutputMegapixels * MEGA &&
            pixels <= APP_MAX_OUTPUT_MEGAPIXELS * MEGA
    }

    /**
     * The multipart fields after `image`. Snake case for what the OpenAPI schema defines; the
     * model pages' camelCase for model settings, which the schema passes through as extra fields.
     *
     * Face recovery is sent as `false` when off: unprovided settings are filled in by Topaz's
     * Autopilot, and "off by default" must not mean "whatever Autopilot decides".
     */
    internal fun formFields(): List<Pair<String, String>> = buildList {
        add("model" to purpose.wireModel)
        add("output_format" to OUTPUT_FORMAT)
        // Width only: the API scales the other side proportionally, so it never letterboxes.
        if (purpose.endpoint.takesOutputSize) add("output_width" to outputWidth.toString())
        if (purpose.faceRecovery) {
            add("faceEnhancement" to options.faceRecovery.toString())
            if (options.faceRecovery) {
                add("faceEnhancementStrength" to FACE_STRENGTH)
                add("faceEnhancementCreativity" to FACE_CREATIVITY)
            }
        }
        options.sharpenStrength?.let { add("sharpenStrength" to "%.2f".format(java.util.Locale.US, it)) }
    }

    /** `/estimate` fields: the schema's own, priced on the output size (§4). */
    internal fun estimateFields(): List<Pair<String, String>> = buildList {
        add("input_width" to inputWidth.toString())
        add("input_height" to inputHeight.toString())
        add("category" to purpose.endpoint.estimateCategory)
        add("model" to purpose.wireModel)
        add("output_format" to OUTPUT_FORMAT)
        if (purpose.endpoint.takesOutputSize) {
            add("output_width" to outputWidth.toString())
            add("output_height" to outputHeight.toString())
        }
    }

    companion object {
        /** JPEG out, the API's default: a 4× PNG of a 12MP photo runs to hundreds of megabytes. */
        const val OUTPUT_FORMAT = "jpeg"
        const val OUTPUT_MIME_TYPE = "image/jpeg"

        /** The schema's 1–32000 bound on either side. */
        const val MAX_SIDE_PX = 32_000L

        /**
         * The app's download ceiling, not a model's (§3): a 4× of a 4096×3072 working render is
         * 201MP. The file is streamed and never decoded whole, so this bounds disk and transfer.
         */
        const val APP_MAX_OUTPUT_MEGAPIXELS = 256L

        /** The model pages give a 0–1 range and no default; the midpoint is the decision (D098). */
        private const val FACE_STRENGTH = "0.5"
        private const val FACE_CREATIVITY = "0.5"
        private const val MEGA = 1_000_000L

        private fun megapixels(width: Int, height: Int): Double =
            width.toDouble() * height / MEGA
    }
}

/** `/estimate`'s answer. [credits] is at least 1 by schema; there is no free tier to show. */
data class TopazEstimate(val credits: Int, val durationSeconds: Int)

/** specs/topaz_enhancement.md §2: what the progress overlay says. No invented percentages. */
sealed interface TopazStage {
    data object Uploading : TopazStage

    /** The job exists and is billable from here on; [processId] is what a cancel names. */
    data class Submitted(val processId: String) : TopazStage

    data object Queued : TopazStage

    /** [progress] is the API's own 0–100, or null when it reported none yet. */
    data class Processing(val progress: Int?) : TopazStage

    data object Downloading : TopazStage
}

/** A downloaded, validated result file. Never decoded whole (§5). */
data class TopazOutput(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val bytes: Long,
    /** "Credits (to be) consumed" from the last status, when the API reported it. */
    val credits: Int?,
)

/**
 * specs/topaz_enhancement.md §4. Distinct because each asks the user for a different action.
 * `AppError` has no case for most of these, so — as `Sam3Outcome` does for SAM 3 — Topaz carries
 * its own.
 */
enum class TopazFailure {
    NotConfigured,
    Unauthorized,
    InsufficientCredits,
    RateLimited,
    TooLarge,
    Rejected,
    Offline,
    Timeout,
    Server,
    DiskFull,

    /** The POST may or may not have created a job; it is never re-sent (§4). */
    SubmitUnknown,
    JobFailed,
    JobCancelled,

    /** A status nobody documented, malformed JSON, an unexpected redirect. Never "done". */
    BadResponse,

    /** The downloaded file is not the image that was asked for. */
    OutputInvalid,
    InputUnsupported,
    InputHasAlpha,
    OptionUnsupported,
    LimitExceeded,
}

sealed interface TopazOutcome<out T> {
    data class Success<T>(val value: T) : TopazOutcome<T>

    /** [retryAfterMs] is a 429's `Retry-After`, when the server sent one. */
    data class Failure(val failure: TopazFailure, val retryAfterMs: Long? = null) : TopazOutcome<Nothing>
}

/**
 * specs/topaz_enhancement.md §4. The whole Topaz surface the editor sees. It knows purposes,
 * options, files and progress — never `EditDocument`, Compose or navigation.
 */
interface TopazProvider {

    /** The current config; `feature:editor` reads [TopazConfig.generation] for run identity. */
    val config: kotlinx.coroutines.flow.StateFlow<TopazConfig>

    /** A free price check. It creates no job. */
    suspend fun estimate(job: TopazJob): TopazOutcome<TopazEstimate>

    /**
     * Submit → poll → download into [output] → validate. Exactly one submit per call, never
     * retried. Cancelling the caller stops polling and downloading at once and asks Topaz to
     * cancel the job, best effort, under the key the run started with.
     */
    suspend fun enhance(
        job: TopazJob,
        input: File,
        output: File,
        onStage: (TopazStage) -> Unit,
    ): TopazOutcome<TopazOutput>
}
