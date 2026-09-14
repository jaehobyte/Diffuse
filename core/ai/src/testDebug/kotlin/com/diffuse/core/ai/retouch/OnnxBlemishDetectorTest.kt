package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * specs/skin_retouch_validation.md §4 (Provider), `work/tasks.md` requirements 8 and 9.
 *
 * The adapter's boundary, without a model: no ONNX Runtime session is ever created here, so what
 * is checked is the part a device cannot check conveniently — that "no model", "wrong model" and
 * "closed" are three different, reported outcomes rather than a crash or a silent empty result.
 *
 * Real inference is `AcneDetectorDeviceTest` in `src/androidTest`, run by hand against a device
 * with a model pushed into place (see `scripts/retouch/README.md`).
 */
@RunWith(RobolectricTestRunner::class)
class OnnxBlemishDetectorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    /** Not installed is `Unavailable`: this build simply has no detector, and nothing is broken. */
    @Test
    fun `a build with no model reports unavailable rather than failing`() = runTest {
        val result = detector().detect(roi())

        assertEquals(Result.Failure(AppError.Unavailable), result)
    }

    @Test
    fun `a model that does not match its manifest is reported as invalid`() = runTest {
        File(folder.root, "acne_640_fp32.onnx").writeText("not the exported graph")
        File(folder.root, "detector.manifest.json").writeText(MANIFEST_WITH_WRONG_DIGEST)

        val result = detector().detect(roi())

        assertTrue(result is Result.Failure && result.error is AppError.Invalid)
    }

    @Test
    fun `an unreadable manifest is invalid, not unavailable`() = runTest {
        File(folder.root, "detector.manifest.json").writeText("{ this is not json")

        val result = detector().detect(roi())

        assertTrue(result is Result.Failure && result.error is AppError.Invalid)
    }

    /**
     * requirement 9: after `close`, a request reports `Unavailable` rather than quietly building
     * a second session — and closing twice is not an error.
     */
    @Test
    fun `after close the detector reports unavailable, and closing twice is safe`() = runTest {
        val detector = detector()

        detector.close()
        detector.close()

        assertEquals(Result.Failure(AppError.Unavailable), detector.detect(roi()))
    }

    @Test
    fun `the version says nothing was loaded until a model is`() {
        assertEquals("acne-yolov8m-onnx@unloaded", detector().detectorVersion)
    }

    private fun detector() = OnnxBlemishDetector(DetectorModelStore(folder.root), dispatchers)

    private fun roi(size: Int = 32): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private companion object {
        val MANIFEST_WITH_WRONG_DIGEST = """
            {
              "manifest_version": 1,
              "model": {"file": "acne_640_fp32.onnx", "sha256": "${"00".repeat(32)}", "bytes": 22},
              "io": {
                "inputs": [{"name": "images", "shape": [1, 3, 640, 640], "dtype": "tensor(float)"}],
                "outputs": [{"name": "output0", "shape": [1, 5, 8400], "dtype": "tensor(float)"}]
              },
              "preprocess": {
                "color": "RGB", "dtype": "float32", "scale": 0.00392156862745098,
                "layout": "NCHW", "resize": "letterbox", "scale_up": true,
                "interpolation": "bilinear-half-pixel-centers", "pad_value": [114, 114, 114],
                "pad_placement": "centred; an odd remainder goes to the bottom and the right",
                "transparent_background": [114, 114, 114]
              },
              "decode": {
                "layout": "[1, 4 + num_classes, anchors]",
                "num_classes": 1, "acne_class_ids": [0],
                "box_format": "cxcywh", "box_units": "model input pixels",
                "objectness": false,
                "class_activation": "already applied in the graph; do not sigmoid again",
                "embedded_nms": false
              }
            }
        """.trimIndent()
    }
}
