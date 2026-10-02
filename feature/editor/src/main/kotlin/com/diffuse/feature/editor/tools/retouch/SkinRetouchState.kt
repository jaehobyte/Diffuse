package com.diffuse.feature.editor.tools.retouch

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CorrectionOutcome
import com.diffuse.core.ai.KindSupport
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.UnsupportedReason
import com.diffuse.core.ai.retouch.server.RetouchServerSkinRetouchProvider
import com.diffuse.core.common.AppError
import com.diffuse.feature.editor.R
import java.io.InterruptedIOException

/** specs/skin_retouch.md §5: where the face analysis of the open session stands. */
enum class SkinAnalysis { None, Analyzing, NoFace, Failed, Ready }

/** specs/skin_retouch.md §5 서버 행: what the last probe of the retouch server said. */
enum class SkinServerStatus { Checking, Ready, NeedsSettings, InvalidAddress, Unauthorized, NotReady, Unreachable }

/** One face in the chooser. [thumbnail] is cut from the session's own base, never persisted. */
data class SkinFaceChoice(val id: String, val thumbnail: ImageBitmap?)

/**
 * specs/skin_retouch.md §3–§5. Sheet state; nothing in it is in the document until 적용.
 *
 * Strengths are the sheet's 0..100 integers. [outcomes] holds the kinds whose candidate is
 * prepared for the selected face, so a slider over a prepared kind composites locally and a slider
 * over an unprepared one asks for 미리보기 instead (§4).
 */
data class SkinRetouchState(
    val analysis: SkinAnalysis = SkinAnalysis.None,
    val faces: List<SkinFaceChoice> = emptyList(),
    val selectedFaceId: String? = null,
    val faceSupport: Map<SkinRetouchKind, KindSupport> = emptyMap(),
    val server: SkinServerStatus = SkinServerStatus.Checking,
    val serverKinds: Set<SkinRetouchKind> = emptySet(),
    val serverHost: String = "",
    val strengths: Map<SkinRetouchKind, Int> = ZERO_STRENGTHS,
    val outcomes: Map<SkinRetouchKind, CorrectionOutcome> = emptyMap(),
    val preparing: Boolean = false,
    val applying: Boolean = false,
    /** The canvas shows a draft for exactly the current strengths. */
    val draftReady: Boolean = false,
    @StringRes val message: Int? = null,
) {

    val busy: Boolean get() = preparing || applying

    fun kindEnabled(kind: SkinRetouchKind): Boolean =
        analysis == SkinAnalysis.Ready && faceSupport[kind] == KindSupport.Supported && kind in serverKinds

    /** The one-line reason a kind's slider is off, or null when it is on. */
    @StringRes
    fun kindReason(kind: SkinRetouchKind): Int? = when {
        analysis != SkinAnalysis.Ready -> null
        kind !in serverKinds -> R.string.skin_retouch_unsupported
        else -> when ((faceSupport[kind] as? KindSupport.Unsupported)?.reason) {
            UnsupportedReason.FaceTooSmall -> R.string.skin_retouch_face_too_small
            UnsupportedReason.ExtremeAngle -> R.string.skin_retouch_extreme_angle
            UnsupportedReason.MissingRegions -> R.string.skin_retouch_missing_regions
            null -> null
        }
    }

    private val activeKinds: List<SkinRetouchKind>
        get() = strengths.filter { (kind, value) -> value > 0 && kindEnabled(kind) }.keys.toList()

    /** A kind above zero with no candidate yet: §4 "갱신 필요". */
    val needsPreview: Boolean get() = activeKinds.any { it !in outcomes }

    val canPreview: Boolean
        get() = !busy && needsPreview && server == SkinServerStatus.Ready

    /** §4: every needed candidate and the local render are ready, and something would change. */
    val canApply: Boolean
        get() = !busy && draftReady && !needsPreview &&
            activeKinds.any { outcomes[it] == CorrectionOutcome.Corrected }

    val needsSettings: Boolean
        get() = server == SkinServerStatus.NeedsSettings || server == SkinServerStatus.InvalidAddress ||
            server == SkinServerStatus.Unauthorized

    companion object {
        const val MAX_STRENGTH = 100
        val ZERO_STRENGTHS: Map<SkinRetouchKind, Int> = SkinRetouchKind.entries.associateWith { 0 }
    }
}

internal fun serverStatusOf(checking: Boolean, availability: Availability): SkinServerStatus = when {
    checking -> SkinServerStatus.Checking
    availability is Availability.Ready -> SkinServerStatus.Ready
    else -> when (val reason = (availability as Availability.Unavailable).reason) {
        is AppError.Invalid ->
            if (reason.detail == RetouchServerSkinRetouchProvider.NO_SERVER) {
                SkinServerStatus.NeedsSettings
            } else {
                SkinServerStatus.InvalidAddress
            }
        AppError.Unauthorized -> SkinServerStatus.Unauthorized
        is AppError.Io -> SkinServerStatus.Unreachable
        else -> SkinServerStatus.NotReady
    }
}

/** §5: one sentence per failure the user can do something different about. */
@StringRes
internal fun prepareMessageFor(error: AppError): Int = when {
    error is AppError.Io && error.cause is InterruptedIOException -> R.string.skin_retouch_timeout
    error is AppError.Io -> R.string.skin_retouch_unreachable
    error == AppError.Unauthorized -> R.string.skin_retouch_unauthorized
    error == AppError.Unavailable -> R.string.skin_retouch_not_ready
    error == AppError.TooLarge -> R.string.skin_retouch_too_large
    error is AppError.Invalid && error.detail == RetouchServerSkinRetouchProvider.NO_SERVER ->
        R.string.skin_retouch_needs_server
    error is AppError.Invalid && error.detail == RetouchServerSkinRetouchProvider.INVALID_ADDRESS ->
        R.string.skin_retouch_invalid_address
    else -> R.string.skin_retouch_failed
}

@StringRes
internal fun serverStatusRes(status: SkinServerStatus): Int = when (status) {
    SkinServerStatus.Checking -> R.string.skin_retouch_checking
    SkinServerStatus.Ready -> R.string.skin_retouch_server_ready
    SkinServerStatus.NeedsSettings -> R.string.skin_retouch_needs_server
    SkinServerStatus.InvalidAddress -> R.string.skin_retouch_invalid_address
    SkinServerStatus.Unauthorized -> R.string.skin_retouch_unauthorized
    SkinServerStatus.NotReady -> R.string.skin_retouch_not_ready
    SkinServerStatus.Unreachable -> R.string.skin_retouch_unreachable
}
