package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.cos
import kotlin.math.sin

/**
 * specs/skin_retouch_validation.md §4 (Analyzer). What editor tests analyse faces through, so no
 * test needs Play services. By default it finds nothing; [faces] places synthetic frontal faces
 * whose contours are ellipses in the ML Kit groups, so `SkinAllowedMask` has real regions to cut.
 */
class FakeFaceRegionAnalyzer(
    var faces: List<Rect> = emptyList(),
    var failAnalysis: Boolean = false,
    var failDetail: Boolean = false,
) : FaceRegionAnalyzer {

    var analyzeCount: Int = 0
        private set

    var detailCount: Int = 0
        private set

    /** Held by the next [analyze] / [detail] call until completed; cancellation propagates like ML Kit's. */
    var analyzeGate: CompletableDeferred<Unit>? = null
    var detailGate: CompletableDeferred<Unit>? = null

    /** The sizes the analyzer was handed, so a test can prove the base is the working canvas. */
    val analyzedSizes: List<Pair<Int, Int>> get() = _analyzedSizes.toList()
    private val _analyzedSizes = mutableListOf<Pair<Int, Int>>()

    override suspend fun analyze(image: Bitmap): Result<FaceAnalysis> {
        analyzeCount++
        _analyzedSizes += image.width to image.height
        analyzeGate?.also { analyzeGate = null }?.await()
        if (failAnalysis) return Result.Failure(AppError.Unavailable)
        // The analyzer's own rules (20% context, largest first), restated: they are internal to
        // core:ai and this fake is compiled into feature:editor's tests too.
        val detected = faces.mapIndexed { index, bounds ->
            val padX = (bounds.width() * CONTEXT).toInt()
            val padY = (bounds.height() * CONTEXT).toInt()
            val roi = Rect(
                (bounds.left - padX).coerceAtLeast(0),
                (bounds.top - padY).coerceAtLeast(0),
                (bounds.right + padX).coerceAtMost(image.width),
                (bounds.bottom + padY).coerceAtMost(image.height),
            )
            DetectedFace("face-$index", bounds, roi)
        }.sortedWith(
            compareByDescending<DetectedFace> { it.bounds.width() * it.bounds.height() }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left },
        )
        return Result.Success(FaceAnalysis(detected))
    }

    override suspend fun detail(roi: Bitmap, face: DetectedFace): Result<FaceDetail> {
        detailCount++
        detailGate?.also { detailGate = null }?.await()
        if (failDetail) return Result.Failure(AppError.Unavailable)
        if (roi.width != face.roi.width() || roi.height != face.roi.height()) {
            return Result.Failure(AppError.Invalid("roi bitmap is not the face roi"))
        }
        val geometry = geometryFor(face.bounds)
        val support: KindSupport = if (minOf(face.bounds.width(), face.bounds.height()) < MIN_FACE_PX) {
            KindSupport.Unsupported(UnsupportedReason.FaceTooSmall)
        } else {
            KindSupport.Supported
        }
        return Result.Success(FaceDetail(face, geometry, SkinRetouchKind.entries.associateWith { support }))
    }

    private fun geometryFor(bounds: Rect): FaceGeometry {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        fun at(fx: Float, fy: Float) = PointF(cx + fx * w, cy + fy * h)
        val leftEye = at(-0.2f, -0.12f)
        val rightEye = at(0.2f, -0.12f)
        val mouth = at(0f, 0.25f)
        val nose = at(0f, 0.1f)
        return FaceGeometry(
            yawDegrees = 0f,
            rollDegrees = 0f,
            regions = mapOf(
                FaceRegion.FaceOval to ellipse(cx, cy, w * 0.46f, h * 0.49f, OVAL_POINTS),
                FaceRegion.LeftEye to ellipse(leftEye.x, leftEye.y, w * 0.09f, h * 0.03f, EYE_POINTS),
                FaceRegion.RightEye to ellipse(rightEye.x, rightEye.y, w * 0.09f, h * 0.03f, EYE_POINTS),
                FaceRegion.LeftEyebrow to ellipse(leftEye.x, leftEye.y - h * 0.08f, w * 0.11f, h * 0.02f, BROW_POINTS),
                FaceRegion.RightEyebrow to ellipse(rightEye.x, rightEye.y - h * 0.08f, w * 0.11f, h * 0.02f, BROW_POINTS),
                FaceRegion.UpperLip to ellipse(mouth.x, mouth.y - h * 0.015f, w * 0.14f, h * 0.02f, LIP_POINTS),
                FaceRegion.LowerLip to ellipse(mouth.x, mouth.y + h * 0.015f, w * 0.13f, h * 0.025f, LIP_POINTS),
                FaceRegion.NoseBridge to listOf(at(0f, -0.1f), nose),
                FaceRegion.NoseBottom to listOf(at(-0.06f, 0.1f), nose, at(0.06f, 0.1f)),
            ),
        )
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int) = (0 until n).map {
        val t = 2 * Math.PI * it / n
        PointF(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
    }

    private companion object {
        const val CONTEXT = 0.2f
        const val MIN_FACE_PX = 200
        const val OVAL_POINTS = 36
        const val EYE_POINTS = 16
        const val BROW_POINTS = 10
        const val LIP_POINTS = 20
    }
}
