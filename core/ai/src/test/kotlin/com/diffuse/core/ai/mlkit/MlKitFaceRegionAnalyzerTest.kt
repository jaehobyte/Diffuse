package com.diffuse.core.ai.mlkit

import android.graphics.Bitmap
import android.graphics.Rect
import com.diffuse.core.ai.DetectedFace
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.cancellation.CancellationException

/**
 * specs/skin_retouch_validation.md §4 (Analyzer): what the analyzer decides around the detector.
 *
 * ML Kit itself needs Play services, so the detector is faked at [FaceScanner] — the seam that
 * exists for exactly this. What is checked here is the boundary the sheet depends on: no face
 * against could not look, a re-detected face that is somebody else, and a cancelled analysis
 * releasing the detector without delivering a late answer. Detection quality is not tested here
 * and cannot be: it needs a device (see work/RESULT.md).
 */
@RunWith(RobolectricTestRunner::class)
class MlKitFaceRegionAnalyzerTest {

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    /** A detector under the test's control. [faces] is what one scan answers. */
    private class FakeScanner(private val faces: suspend () -> List<Face>?) : FaceScanner {
        var opened = 0
        var closed = 0

        override fun open(options: FaceDetectorOptions): FaceScan {
            opened++
            return object : FaceScan {
                override suspend fun faces(image: Bitmap) = this@FakeScanner.faces()
                override fun close() {
                    closed++
                }
            }
        }
    }

    // ---- analyze ---------------------------------------------------------

    @Test
    fun `a photo with no face is a success carrying no faces`() = runTest {
        val analyzer = analyzer(FakeScanner { emptyList() })

        val result = analyzer.analyze(image(WIDTH, HEIGHT))

        assertEquals(emptyList<DetectedFace>(), (result as Result.Success).value.faces)
    }

    /** specs/skin_retouch.md §5: the sheet owes the user a different sentence for this one. */
    @Test
    fun `a detector that could not look is Unavailable, not an empty result`() = runTest {
        val analyzer = analyzer(FakeScanner { null })

        val result = analyzer.analyze(image(WIDTH, HEIGHT))

        assertEquals(AppError.Unavailable, (result as Result.Failure).error)
    }

    @Test
    fun `the detector is released after a scan that failed`() = runTest {
        val scanner = FakeScanner { null }

        analyzer(scanner).analyze(image(WIDTH, HEIGHT))

        assertEquals(1, scanner.opened)
        assertEquals(1, scanner.closed)
    }

    // ---- detail ----------------------------------------------------------

    @Test
    fun `a roi bitmap that is not the face roi never reaches the detector`() = runTest {
        val scanner = FakeScanner { emptyList() }

        val result = analyzer(scanner).detail(image(10, 10), FACE)

        assertTrue((result as Result.Failure).error is AppError.Invalid)
        assertEquals(0, scanner.opened)
    }

    /**
     * specs/skin_retouch_pipeline.md §3: a face re-detected inside the ROI that is not the one
     * asked about is a failure, never a guess. An empty re-detection is the same answer.
     */
    @Test
    fun `a face that is not re-detected in its own roi is a failure, not a guess`() = runTest {
        val analyzer = analyzer(FakeScanner { emptyList() })

        val result = analyzer.detail(roiImage(), FACE)

        assertTrue((result as Result.Failure).error is AppError.Invalid)
    }

    @Test
    fun `detail reports Unavailable when the detector could not look`() = runTest {
        val analyzer = analyzer(FakeScanner { null })

        val result = analyzer.detail(roiImage(), FACE)

        assertEquals(AppError.Unavailable, (result as Result.Failure).error)
    }

    // ---- cancellation ----------------------------------------------------

    /** specs/skin_retouch_pipeline.md §2: `CancellationException` propagates, resources are freed. */
    @Test
    fun `a cancelled analysis releases the detector and never delivers a late answer`() = runTest {
        val gate = CompletableDeferred<List<Face>?>()
        val scanner = FakeScanner { gate.await() }
        val analyzer = analyzer(scanner)
        var delivered: Result<*>? = null
        var thrown: Throwable? = null

        // Started here and now, so the scan is already waiting when the cancellation arrives.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delivered = analyzer.analyze(image(WIDTH, HEIGHT))
            } catch (cancelled: CancellationException) {
                thrown = cancelled
            }
        }
        job.cancel()
        // The detector answers after the caller has gone: the answer must go nowhere.
        gate.complete(emptyList())
        job.join()

        assertEquals(1, scanner.closed)
        assertNull(delivered)
        assertTrue(thrown is CancellationException)
    }

    private fun analyzer(scanner: FakeScanner) = MlKitFaceRegionAnalyzer(dispatchers, scanner)

    private fun image(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun roiImage(): Bitmap = image(FACE.roi.width(), FACE.roi.height())

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 900
        val FACE = DetectedFace("face-1", Rect(400, 300, 800, 700), Rect(320, 220, 880, 780))
    }
}
