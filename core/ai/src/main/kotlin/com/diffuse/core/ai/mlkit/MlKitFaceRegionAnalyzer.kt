package com.diffuse.core.ai.mlkit

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.diffuse.core.ai.DetectedFace
import com.diffuse.core.ai.FaceAnalysis
import com.diffuse.core.ai.FaceDetail
import com.diffuse.core.ai.FaceGeometry
import com.diffuse.core.ai.FaceRegion
import com.diffuse.core.ai.FaceRegionAnalyzer
import com.diffuse.core.ai.isSameFace
import com.diffuse.core.ai.orderedBySelection
import com.diffuse.core.ai.roiFor
import com.diffuse.core.ai.supportOf
import com.diffuse.core.ai.toCanonical
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * specs/skin_retouch_pipeline.md §1, §3. Face geometry for retouching, on device, through the same
 * Play-services ML Kit the portrait menu hint already uses — a reused dependency, not a new one.
 *
 * Two passes, for the reason §3 gives: ML Kit returns contours for the **most prominent face
 * only**, so a single pass over a group photo would hand one person's contours to everybody. The
 * first pass asks the whole canvas for boxes; the second asks the selected face's ROI, where that
 * face is by construction the prominent one, and maps the points back out.
 *
 * Unlike [com.diffuse.core.ai.PortraitDetector], a failure here is reportable: the user tapped a
 * tool and is owed the difference between "no face in this photo" and "we could not look".
 */
@Singleton
class MlKitFaceRegionAnalyzer internal constructor(
    private val dispatchers: DispatcherProvider,
    private val scanner: FaceScanner,
) : FaceRegionAnalyzer {

    @Inject
    constructor(dispatchers: DispatcherProvider, logger: Logger) :
        this(dispatchers, FaceScanner { options -> MlKitFaceScan(options, logger) })

    override suspend fun analyze(image: Bitmap): Result<FaceAnalysis> =
        withContext(dispatchers.default) {
            val faces = facesIn(image, BOUNDS_OPTIONS)
                ?: return@withContext Result.Failure(AppError.Unavailable)
            val detected = faces.map { face ->
                val bounds = face.boundingBox.clippedTo(image.width, image.height)
                DetectedFace(
                    id = newId(),
                    bounds = bounds,
                    roi = roiFor(bounds, image.width, image.height),
                )
            }
            Result.Success(FaceAnalysis(orderedBySelection(detected)))
        }

    override suspend fun detail(roi: Bitmap, face: DetectedFace): Result<FaceDetail> =
        withContext(dispatchers.default) {
            if (roi.width != face.roi.width() || roi.height != face.roi.height()) {
                return@withContext Result.Failure(AppError.Invalid("roi bitmap is not the face roi"))
            }
            val faces = facesIn(roi, CONTOUR_OPTIONS)
                ?: return@withContext Result.Failure(AppError.Unavailable)
            val match = faces.firstOrNull {
                isSameFace(face.bounds, it.boundingBox.movedTo(face.roi))
            } ?: return@withContext Result.Failure(AppError.Invalid("selected face not found in roi"))

            val geometry = match.geometryIn(face.roi)
            Result.Success(FaceDetail(face, geometry, supportOf(face.bounds, geometry)))
        }

    /** Every contour ML Kit gave, in canonical coordinates. A group it did not give stays out. */
    private fun Face.geometryIn(roi: Rect) = FaceGeometry(
        yawDegrees = headEulerAngleY,
        rollDegrees = headEulerAngleZ,
        regions = FaceRegion.entries.mapNotNull { region ->
            val points = CONTOURS.getValue(region)
                .flatMap { getContour(it)?.points.orEmpty() }
                .map { toCanonical(PointF(it.x, it.y), roi) }
            if (points.isEmpty()) null else region to points
        }.toMap(),
    )

    /**
     * `null` is "we could not look": a model Play has not delivered, a detector the device
     * refused, or a frame ML Kit would not read. The caller turns that into `Unavailable`.
     */
    private suspend fun facesIn(image: Bitmap, options: FaceDetectorOptions): List<Face>? {
        val scan = scanner.open(options)
        return try {
            scan.faces(image)
        } finally {
            // Cancellation lands here too, so the native detector is released either way.
            scan.close()
        }
    }

    private fun Rect.clippedTo(width: Int, height: Int) = Rect(
        left.coerceIn(0, width),
        top.coerceIn(0, height),
        right.coerceIn(0, width),
        bottom.coerceIn(0, height),
    )

    /** An ROI-local box in canonical coordinates, so it can be compared with the selected face. */
    private fun Rect.movedTo(roi: Rect) = Rect(
        left + roi.left,
        top + roi.top,
        right + roi.left,
        bottom + roi.top,
    )

    private companion object {
        /**
         * Boxes only: contours here would be spent on faces the user has not selected, and the
         * prominent-face limit means most of them would come back empty anyway.
         *
         * The floor is relative to the image width, so on a 4096 canonical canvas it is roughly
         * the 200px size bar itself. On a smaller canvas it lets faces through that
         * `supportOf` then disables with a reason, which is what the sheet needs to show.
         */
        val BOUNDS_OPTIONS: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE_RATIO)
            .build()

        /** The selected face, close up. Contours are the whole point of this pass. */
        val CONTOUR_OPTIONS: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        const val MIN_FACE_SIZE_RATIO = 0.05f

        /**
         * Each region as the ML Kit contours it is made of. Brows and lips are two contours each
         * — a top edge and a bottom one — and a mask needs both to enclose the feature.
         */
        val CONTOURS: Map<FaceRegion, List<Int>> = mapOf(
            FaceRegion.FaceOval to listOf(FaceContour.FACE),
            FaceRegion.LeftEye to listOf(FaceContour.LEFT_EYE),
            FaceRegion.RightEye to listOf(FaceContour.RIGHT_EYE),
            FaceRegion.LeftEyebrow to
                listOf(FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM),
            FaceRegion.RightEyebrow to
                listOf(FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM),
            FaceRegion.UpperLip to
                listOf(FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM),
            FaceRegion.LowerLip to
                listOf(FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM),
            FaceRegion.NoseBridge to listOf(FaceContour.NOSE_BRIDGE),
            FaceRegion.NoseBottom to listOf(FaceContour.NOSE_BOTTOM),
        )
    }
}

/**
 * One open detector. specs/skin_retouch_validation.md §4 asks the analyzer's decisions — no face
 * against could not look, the re-detection identity check, cancellation and detector release — to
 * be verified automatically, and none of them can be reached through Play services in a JVM test.
 * They are all above this interface; only the ML Kit call itself is below it.
 */
internal interface FaceScan : Closeable {

    /** The faces in [image], or `null` for "we could not look". */
    suspend fun faces(image: Bitmap): List<Face>?
}

/** Opens a detector for [FaceDetectorOptions]. The caller closes it, cancelled or not. */
internal fun interface FaceScanner {
    fun open(options: FaceDetectorOptions): FaceScan
}

private class MlKitFaceScan(
    options: FaceDetectorOptions,
    private val logger: Logger,
) : FaceScan {

    private val detector = FaceDetection.getClient(options)

    override suspend fun faces(image: Bitmap): List<Face>? =
        suspendCancellableCoroutine { continuation ->
            detector.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { faces -> continuation.resume(faces) }
                .addOnFailureListener { error ->
                    logger.warn(TAG, "face analysis unavailable", error)
                    continuation.resume(null)
                }
        }

    override fun close() = detector.close()
}

private const val TAG = "FaceRegionAnalyzer"
