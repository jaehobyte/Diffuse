package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import android.graphics.RectF
import com.diffuse.core.common.Result

/**
 * specs/skin_retouch_pipeline.md §1.1, §2. One blemish the detector believes it found, in the
 * pixel coordinates of the ROI that was handed in.
 *
 * @param box `xyxy`, already clipped to the ROI and guaranteed non-degenerate — `left < right`
 * and `top < bottom`. A caller never has to check for a box turned inside out by the letterbox
 * inversion, or for one lying in the padding: those never leave the decoder.
 * @param confidence the model's own score, unmodified. Not a probability of anything the product
 * promises; it is what the threshold in [DetectorSettings] was compared against.
 * @param classId the model's **original** class id, not a re-numbered one. specs/skin_retouch_-
 * pipeline.md §6 records the detector version with the correction, and a re-numbered class would
 * make an old saved document unreadable against a later checkpoint.
 */
data class BlemishDetection(
    val box: RectF,
    val confidence: Float,
    val classId: Int,
)

/**
 * Every blemish found in one ROI.
 *
 * An empty [detections] list is a **success**: the detector looked at this face and found nothing
 * of its kind. specs/skin_retouch_pipeline.md §2 keeps that apart from "no model", "corrupt
 * model", "incompatible model" and "inference failed", all of which are
 * [com.diffuse.core.common.Result.Failure] — the sheet owes the user different sentences.
 *
 * @param hitDetectionLimit the run stopped at [DetectorSettings.maxDetections]. Recorded because
 * a capped run's recall figure is capped too (`work/tasks.md` requirement 6).
 * @param detectorVersion identifies the checkpoint and export that produced this, for the
 * per-kind version the saved operation carries (§6). Free text for the document, never shown.
 */
data class BlemishDetections(
    val detections: List<BlemishDetection>,
    val detectorVersion: String,
    val hitDetectionLimit: Boolean = false,
)

/**
 * specs/skin_retouch_pipeline.md §1.1: **finding** blemishes, which is not repairing them.
 *
 * Deliberately not part of [com.diffuse.core.ai.SkinRetouchProvider]. §1.1 says MI-GAN does not
 * find defects and a face rectangle must never be handed to it as a restoration mask, so the two
 * halves are separate contracts that a provider composes — and either can be evaluated alone,
 * which is what specs/skin_retouch_validation.md §1.1 splits SR1-A from SR1-B to do.
 *
 * Nothing here knows about `Operation`, the renderer or the editor's session (ai_provider.md §2).
 */
interface BlemishDetector {

    /** Identifies the checkpoint and export behind this instance; carried on every result. */
    val detectorVersion: String

    /**
     * @param faceRoi orientation-normalised, immutable `ARGB_8888` for **one** face, at canonical
     * working coordinates and 1:1 scale. The caller keeps ownership and the detector never writes
     * to it; EXIF, face detection, cropping and any display transform are the caller's job
     * (`work/tasks.md` requirement 5). Downscaling the whole photograph to 640 instead of passing
     * a face ROI is not the same call and gives a face a handful of pixels to be found in.
     * @return the detections, or a failure. `CancellationException` propagates rather than
     * becoming an error, and a cancelled request never returns a result.
     */
    suspend fun detect(faceRoi: Bitmap): Result<BlemishDetections>
}
