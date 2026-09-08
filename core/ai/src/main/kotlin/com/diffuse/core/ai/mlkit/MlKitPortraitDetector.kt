package com.diffuse.core.ai.mlkit

import android.graphics.Bitmap
import com.diffuse.core.ai.PortraitDetector
import com.diffuse.core.ai.PortraitResult
import com.diffuse.core.ai.portraitResultOf
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * work/decisions.md T79. The portrait gate, on device, through Google Play services.
 *
 * The options are the cheapest question ML Kit can be asked: FAST mode, and none of the landmark,
 * contour, classification or tracking work. All this needs is a box big enough to retouch, and
 * every one of those extras is per-face cost spent on an answer nobody reads.
 *
 * Nothing here throws or reports an `AppError`. The model arrives with Play services rather than
 * with the APK, so "not downloaded yet" is an ordinary first-run state; T79 answers it with
 * [PortraitResult.Unknown], the menu falls back to the general one, and the user is told nothing.
 */
@Singleton
class MlKitPortraitDetector @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : PortraitDetector {

    override suspend fun detect(image: Bitmap): PortraitResult =
        withContext(dispatchers.default) {
            val detector = FaceDetection.getClient(OPTIONS)
            try {
                detector.facesIn(image)
                    ?.let { faces -> portraitResultOf(faces.map { it.boundingBox.width() }, image.width) }
                    ?: PortraitResult.Unknown
            } finally {
                // Cancellation lands here too, so the native detector is released either way.
                detector.close()
            }
        }

    /**
     * `null` is "we could not look": a missing model, a detector the device refused, or a frame
     * ML Kit would not read. The caller turns that into [PortraitResult.Unknown].
     */
    private suspend fun FaceDetector.facesIn(image: Bitmap): List<Face>? =
        suspendCancellableCoroutine { continuation ->
            process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { faces -> continuation.resume(faces) }
                .addOnFailureListener { error ->
                    logger.warn(TAG, "portrait detection unavailable", error)
                    continuation.resume(null)
                }
        }

    private companion object {
        const val TAG = "PortraitDetector"

        val OPTIONS: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    }
}
