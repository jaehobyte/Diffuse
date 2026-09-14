package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.diffuse.core.common.Result

/**
 * specs/skin_retouch_pipeline.md §3. The parts of a face the retouch masks are built from.
 *
 * A group is either present with its points or absent. It is never present with a placeholder:
 * §3 forbids substituting zero for a coordinate ML Kit did not give, because a landmark at the
 * canvas origin would silently move a mask to the corner of the photo.
 */
enum class FaceRegion {
    FaceOval,
    LeftEye,
    RightEye,
    LeftEyebrow,
    RightEyebrow,
    UpperLip,
    LowerLip,
    NoseBridge,
    NoseBottom,
}

/** Why a kind cannot be offered for a face. The sheet turns each into one short Korean line. */
enum class UnsupportedReason { FaceTooSmall, ExtremeAngle, MissingRegions }

/** Per-kind availability **for one face**, which is not the same question as [Availability]. */
sealed interface KindSupport {
    data object Supported : KindSupport
    data class Unsupported(val reason: UnsupportedReason) : KindSupport
}

/**
 * specs/skin_retouch_pipeline.md §3. One face's shape, in canonical canvas coordinates.
 *
 * Session-scoped like [DetectedFace]: no embedding, no identity, nothing persisted (§3).
 */
data class FaceGeometry(
    val yawDegrees: Float,
    val rollDegrees: Float,
    val regions: Map<FaceRegion, List<PointF>>,
)

/**
 * A face found in the canonical image. [id] is ephemeral — it identifies a face within one
 * analysis so a sheet can say which thumbnail is selected, and means nothing afterwards.
 *
 * [roi] is [bounds] plus context, clipped to the canvas: what gets cropped and sent for
 * correction, and the coordinate frame the returned candidate comes back in.
 */
data class DetectedFace(val id: String, val bounds: Rect, val roi: Rect)

/**
 * Every face in the canonical image, in default selection order (skin_retouch.md §3).
 *
 * An empty list is a **success**: there is no face here. Not being able to look — the model has
 * not been downloaded, or detection failed — is `Result.Failure(AppError.Unavailable)` instead,
 * because the sheet owes the user different sentences for the two (skin_retouch.md §5).
 */
data class FaceAnalysis(val faces: List<DetectedFace>)

/** The selected face, close up: what each kind needs to build its mask, and what it may offer. */
data class FaceDetail(
    val face: DetectedFace,
    val geometry: FaceGeometry,
    val support: Map<SkinRetouchKind, KindSupport>,
)

/**
 * specs/skin_retouch_pipeline.md §1, §3. Face geometry for retouching, deliberately separate from
 * [PortraitDetector].
 *
 * The detector answers a menu question about the source photo with a box count; this answers a
 * pixel question about the document being edited, in canonical coordinates, with contours. Sharing
 * one interface would let a menu hint decide what may be corrected, which §1 forbids.
 *
 * Two calls rather than one because ML Kit only returns contours for the most prominent face: the
 * whole canvas gives boxes, and the selected face's ROI — where it *is* the prominent face — gives
 * the contours, mapped back out (§3).
 */
interface FaceRegionAnalyzer {

    /** @param image the canonical working image: orientation-normalised, before crop. */
    suspend fun analyze(image: Bitmap): Result<FaceAnalysis>

    /**
     * @param roi the crop of that same canonical image at [face]`.roi`, at 1:1 scale.
     * @return the face's contours in canonical coordinates, or a failure when the face re-detected
     * inside [roi] is not the one that was asked about (§3) — never a guess.
     */
    suspend fun detail(roi: Bitmap, face: DetectedFace): Result<FaceDetail>
}

/**
 * specs/skin_retouch_pipeline.md §3: [ROI_CONTEXT_RATIO] of each axis on each side, clipped to the
 * canvas. Clipped rather than shifted — a face against the edge of the frame gets less context,
 * and moving the window would take the ROI off the face.
 */
internal fun roiFor(bounds: Rect, canvasWidth: Int, canvasHeight: Int): Rect {
    val padX = (bounds.width() * ROI_CONTEXT_RATIO).toInt()
    val padY = (bounds.height() * ROI_CONTEXT_RATIO).toInt()
    return Rect(
        (bounds.left - padX).coerceAtLeast(0),
        (bounds.top - padY).coerceAtLeast(0),
        (bounds.right + padX).coerceAtMost(canvasWidth),
        (bounds.bottom + padY).coerceAtMost(canvasHeight),
    )
}

/**
 * specs/skin_retouch.md §3: the largest face is the default, ties break upwards then leftwards.
 * A total order, so the same photo always opens on the same face.
 */
internal fun orderedBySelection(faces: List<DetectedFace>): List<DetectedFace> =
    faces.sortedWith(
        compareByDescending<DetectedFace> { it.bounds.width().toLong() * it.bounds.height() }
            .thenBy { it.bounds.top }
            .thenBy { it.bounds.left },
    )

/**
 * specs/skin_retouch_pipeline.md §3. What this face is big, straight and complete enough for.
 *
 * Size and angle are properties of the whole face, so they disqualify every kind at once. Missing
 * contours are per kind: an unreadable mouth says nothing about the skin under the eyes.
 */
internal fun supportOf(bounds: Rect, geometry: FaceGeometry): Map<SkinRetouchKind, KindSupport> {
    val blocked = when {
        minOf(bounds.width(), bounds.height()) < MIN_FACE_PX -> UnsupportedReason.FaceTooSmall
        maxOf(kotlin.math.abs(geometry.yawDegrees), kotlin.math.abs(geometry.rollDegrees)) >
            MAX_HEAD_ANGLE_DEG -> UnsupportedReason.ExtremeAngle
        else -> null
    }
    return SkinRetouchKind.entries.associateWith { kind ->
        when {
            blocked != null -> KindSupport.Unsupported(blocked)
            geometry.hasAll(requiredRegions(kind)) -> KindSupport.Supported
            else -> KindSupport.Unsupported(UnsupportedReason.MissingRegions)
        }
    }
}

/**
 * The contours each kind's mask is built from: the skin it may change, and every neighbour it has
 * to keep out (skin_retouch.md §1). Missing one of these is not a smaller correction, it is an
 * unprotected one, which is why the kind goes off instead.
 */
internal fun requiredRegions(kind: SkinRetouchKind): Set<FaceRegion> = when (kind) {
    // Anywhere on the skin, so every neighbour has to be excluded.
    SkinRetouchKind.Blemish -> SKIN_REGIONS
    // Skin as well, plus the nose, where most of the shine is.
    SkinRetouchKind.Shine -> SKIN_REGIONS + setOf(FaceRegion.NoseBridge, FaceRegion.NoseBottom)
    // Under the eyes only: the eyes locate it and the oval bounds it.
    SkinRetouchKind.DarkCircles -> setOf(FaceRegion.FaceOval, FaceRegion.LeftEye, FaceRegion.RightEye)
    // Philtrum, chin and jaw — below the nose, around but never on the lips.
    SkinRetouchKind.ShavingShadow -> setOf(
        FaceRegion.FaceOval,
        FaceRegion.NoseBottom,
        FaceRegion.UpperLip,
        FaceRegion.LowerLip,
    )
}

/**
 * specs/skin_retouch_pipeline.md §3. Whether the face found inside the ROI is the one the caller
 * selected, rather than a neighbour who happens to overlap the crop.
 */
internal fun isSameFace(requested: Rect, redetected: Rect): Boolean {
    val intersection = Rect(requested)
    if (!intersection.intersect(redetected)) return false
    val overlap = intersection.area()
    val union = requested.area() + redetected.area() - overlap
    return union > 0 && overlap.toFloat() / union >= FACE_MATCH_MIN_IOU
}

/** ROI-local to canonical: the crop is 1:1, so the offset is the whole mapping (§4). */
internal fun toCanonical(point: PointF, roi: Rect): PointF =
    PointF(point.x + roi.left, point.y + roi.top)

private fun Rect.area(): Long = width().toLong() * height()

private fun FaceGeometry.hasAll(required: Set<FaceRegion>): Boolean =
    required.all { regions[it]?.isNotEmpty() == true }

/** Every contour a correction anywhere on the skin has to respect. */
private val SKIN_REGIONS = setOf(
    FaceRegion.FaceOval,
    FaceRegion.LeftEye,
    FaceRegion.RightEye,
    FaceRegion.LeftEyebrow,
    FaceRegion.RightEyebrow,
    FaceRegion.UpperLip,
    FaceRegion.LowerLip,
)

/** specs/skin_retouch_pipeline.md §3. Initial product values; SR1 evidence may revise them. */
internal const val ROI_CONTEXT_RATIO = 0.2f
internal const val MIN_FACE_PX = 200
internal const val MAX_HEAD_ANGLE_DEG = 30f

/** Half the box in common. Below that the ROI found somebody else. */
internal const val FACE_MATCH_MIN_IOU = 0.5f
