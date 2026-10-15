package com.diffuse.feature.editor.tools.enhance

import androidx.compose.ui.graphics.ImageBitmap
import androidx.annotation.StringRes
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ai.topaz.TopazStage
import com.diffuse.feature.editor.R
import java.io.File

/**
 * specs/topaz_enhancement.md §1. Where the shared Topaz sheet was opened from decides its title
 * and which purposes it offers; the sheet, the controller and the provider are the same for all.
 */
enum class TopazGroup(@StringRes val titleRes: Int, val purposes: List<TopazPurpose>) {
    /** AI → 화질 개선, and 디테일's shortcut. */
    Enhance(
        R.string.topaz_title_enhance,
        listOf(
            TopazPurpose.Upscale,
            TopazPurpose.Sharpen,
            TopazPurpose.Denoise,
            TopazPurpose.Restore,
            TopazPurpose.Creative,
            TopazPurpose.DustScratch,
        ),
    ),

    /** 라이트 → AI 조명 보정. */
    Light(R.string.topaz_title_light, listOf(TopazPurpose.Lighting)),

    /** 색상 → AI 색 균형 · 흑백 사진 컬러화. */
    Color(R.string.topaz_title_color, listOf(TopazPurpose.ColorBalance, TopazPurpose.Colorize)),
}

/** §2: the states the sheet tells apart. Ordered as a run moves through them. */
enum class TopazPhase(@StringRes val labelRes: Int) {
    Idle(R.string.topaz_phase_preparing),
    Preparing(R.string.topaz_phase_preparing),
    Uploading(R.string.topaz_phase_uploading),
    Queued(R.string.topaz_phase_queued),
    Processing(R.string.topaz_phase_processing),
    Downloading(R.string.topaz_phase_downloading),
    Saving(R.string.topaz_phase_saving),
    Adopting(R.string.topaz_phase_adopting),
}

/** §4: an estimate is asked for, never assumed; "unknown" is never shown as 0. */
sealed interface TopazEstimateState {
    data object NotChecked : TopazEstimateState
    data object Checking : TopazEstimateState
    data class Known(val credits: Int) : TopazEstimateState
    data object Unknown : TopazEstimateState
}

/** §5: the rendered document this run sends, and how it looked on screen. */
data class TopazInput(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    /** The input at preview size: the "전" of §2's compare. */
    val before: ImageBitmap,
)

/** A validated result file plus what produced it. The file is never decoded whole. */
data class TopazResult(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val purpose: TopazPurpose,
    val options: TopazOptions,
    /** The result at preview size, the same size as [TopazInput.before]: the "후". */
    val after: ImageBitmap,
    val credits: Int?,
)

/**
 * specs/topaz_enhancement.md §2. Sheet state; nothing here reaches the open document. A result
 * becomes a gallery file or a **new** project, never an operation on this one.
 */
data class TopazState(
    /** null while the sheet is closed. */
    val group: TopazGroup? = null,
    val purpose: TopazPurpose = TopazPurpose.Upscale,
    val options: TopazOptions = TopazOptions.defaultsFor(TopazPurpose.Upscale),
    val configured: Boolean = false,
    val input: TopazInput? = null,
    val phase: TopazPhase = TopazPhase.Idle,
    /** The API's own percentage, only while it reports one (§2: no invented numbers). */
    val progress: Int? = null,
    val estimate: TopazEstimateState = TopazEstimateState.NotChecked,
    val result: TopazResult? = null,
    /** Held down: the canvas shows [TopazInput.before] instead of [TopazResult.after]. */
    val comparing: Boolean = false,
    val saved: Boolean = false,
    /** The last run's failure, shown in the sheet with a retry until the next run starts. */
    val failure: TopazFailure? = null,
    @StringRes val message: Int? = null,
) {

    val isOpen: Boolean get() = group != null

    val job: TopazJob? get() = input?.let { TopazJob(purpose, options, it.width, it.height, it.hasAlpha) }

    /** Anything that keeps 실행 disabled, as the line the sheet shows for it. */
    @get:StringRes
    val blocker: Int?
        get() = when {
            !configured -> R.string.topaz_needs_key
            input == null -> R.string.topaz_phase_preparing
            else -> job?.problem()?.let(::blockerFor)
        }

    val canRun: Boolean get() = phase == TopazPhase.Idle && blocker == null

    /** DESIGN.md §4 State display: every phase but idle earns the canvas overlay and its cancel. */
    val working: Boolean get() = phase != TopazPhase.Idle

    /** §2: options changed after the result — it is the previous settings' result. */
    val stale: Boolean get() = result != null && (result.purpose != purpose || result.options != options)

    /** §5: re-editing opens at the working size, so the sheet says so before anything runs. */
    val reopensSmaller: Boolean
        get() = (job?.let { maxOf(it.resultWidth, it.resultHeight) } ?: 0) > REEDIT_LONG_EDGE_PX

    companion object {
        /** `ImageLoader`'s bound (specs/imaging.md). Mirrored, since the loader's is internal. */
        const val REEDIT_LONG_EDGE_PX = 4096
    }
}

/** §2: what the canvas overlay says for a provider stage, and the API's own percentage if any. */
internal fun TopazStage.phase(): Pair<TopazPhase, Int?> = when (this) {
    TopazStage.Uploading, is TopazStage.Submitted -> TopazPhase.Uploading to null
    TopazStage.Queued -> TopazPhase.Queued to null
    is TopazStage.Processing -> TopazPhase.Processing to progress
    TopazStage.Downloading -> TopazPhase.Downloading to null
}

@StringRes
internal fun blockerFor(failure: TopazFailure): Int = when (failure) {
    TopazFailure.InputHasAlpha -> R.string.topaz_blocked_alpha
    TopazFailure.LimitExceeded -> R.string.topaz_blocked_limit
    else -> R.string.topaz_blocked_option
}

/** §4's table: each failure says what the user can do about it. */
// One branch per failure, so it grows with the enum by design.
@Suppress("CyclomaticComplexMethod")
@StringRes
internal fun messageFor(failure: TopazFailure): Int = when (failure) {
    TopazFailure.NotConfigured -> R.string.topaz_needs_key
    TopazFailure.Unauthorized -> R.string.topaz_error_key
    TopazFailure.InsufficientCredits -> R.string.topaz_error_credits
    TopazFailure.RateLimited -> R.string.topaz_error_rate
    TopazFailure.TooLarge, TopazFailure.LimitExceeded -> R.string.topaz_error_too_large
    TopazFailure.Rejected, TopazFailure.InputUnsupported, TopazFailure.OptionUnsupported ->
        R.string.topaz_error_rejected
    TopazFailure.InputHasAlpha -> R.string.topaz_blocked_alpha
    TopazFailure.Offline -> R.string.topaz_error_offline
    TopazFailure.Timeout -> R.string.topaz_error_timeout
    TopazFailure.Server -> R.string.topaz_error_server
    TopazFailure.DiskFull -> R.string.topaz_error_disk
    TopazFailure.SubmitUnknown -> R.string.topaz_error_submit_unknown
    TopazFailure.JobFailed -> R.string.topaz_error_job_failed
    TopazFailure.JobCancelled -> R.string.topaz_error_job_cancelled
    TopazFailure.BadResponse, TopazFailure.OutputInvalid -> R.string.topaz_error_bad_output
}
