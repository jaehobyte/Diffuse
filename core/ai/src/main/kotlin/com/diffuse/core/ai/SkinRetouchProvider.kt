package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/skin_retouch.md §1. The four corrections the sheet offers, each with its own engine and
 * its own slider. They are a product contract, not four outputs of one model: nothing here lets a
 * provider answer one request and copy the picture into the other three (pipeline §2).
 */
enum class SkinRetouchKind { Blemish, Shine, DarkCircles, ShavingShadow }

/**
 * specs/skin_retouch.md §6. Where the pixels are corrected, which the sheet shows the user. There
 * is no third case and no fallback edge between the two: a local failure never becomes an upload.
 */
enum class ExecutionLocation { Local, RetouchServer }

/**
 * specs/skin_retouch_pipeline.md §2. Whether a request changed anything.
 *
 * [NoChange] is a **success**: the engine looked and found nothing of that kind to correct. The
 * sheet says so and the slider does nothing; it is not an error and not "unsupported".
 */
enum class CorrectionOutcome { Corrected, NoChange }

/**
 * specs/skin_retouch_pipeline.md §2, §5. One kind's answer for one face, at reference strength.
 *
 * @param candidate the corrected ROI at strength 100, the same size and coordinates as the ROI
 * that was passed in. Region limiting and feather are **already applied, exactly once** — the
 * client composites with [changeSupport] as a binary mask and never multiplies a soft alpha in
 * again (§2, §6).
 * @param changeSupport `ALPHA_8`, strictly 0 or 255, at the ROI size: where [candidate] may differ
 * from the base. Outside it the candidate is the base, pixel for pixel.
 * @param engineVersion what produced this, recorded per kind in the saved operation (§6). Free
 * text for the document, never shown to the user (skin_retouch.md §1).
 */
data class PreparedCorrection(
    val candidate: Bitmap,
    val changeSupport: Bitmap,
    val outcome: CorrectionOutcome,
    val engineVersion: String,
)

/**
 * specs/skin_retouch_pipeline.md §1–§2. The boundary the editor retouches through, whichever
 * engine is behind it and wherever it runs.
 *
 * One call is **one face and one kind**. Four sliders are four calls off the same immutable base
 * ROI, which is what makes the strengths independent (§5) and what stops a single whole-face
 * result from being presented as four controls (skin_retouch.md §4).
 */
interface SkinRetouchProvider {

    val availability: StateFlow<Availability>

    /** What the sheet shows, and what the settings screen must have made explicit (§8). */
    val executionLocation: ExecutionLocation

    /**
     * The kinds this build can actually correct. A kind outside this set is
     * [com.diffuse.core.common.AppError.Unsupported], not a silent no-op: specs/skin_retouch.md §1
     * requires unfinished kinds to stay visibly disabled rather than appear to work.
     */
    val supportedKinds: Set<SkinRetouchKind>

    /**
     * @param faceRoi orientation-normalised `ARGB_8888` at canonical working coordinates. Never
     * modified: candidates for the other kinds are prepared from this same base.
     * @param allowedMask `ALPHA_8` at [faceRoi]'s exact size — the **most** that may change, with
     * eyes, brows, lips, nostrils, hair and other people already excluded. It is not a defect
     * mask: finding the actual blemishes is the engine's own job, inside this allowance (§2).
     * @return the candidate at reference strength, or a failure. `CancellationException`
     * propagates rather than becoming an error.
     */
    suspend fun prepare(
        faceRoi: Bitmap,
        allowedMask: Bitmap,
        kind: SkinRetouchKind,
    ): Result<PreparedCorrection>
}
