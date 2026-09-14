package com.diffuse.core.ai.retouch

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/**
 * `work/tasks.md` requirements 8, 9 and 11: the acne detector, on a real device, with the real
 * exported ONNX — end to end from a face ROI to boxes to a candidate mask.
 *
 * **Not part of `scripts/check.sh`.** specs/testing.md §3 keeps instrumentation out of the loop's
 * verdict, and this needs a model that is deliberately not in the repository. Every test here
 * skips (`assumeTrue`) when no model is installed, so `connectedDebugAndroidTest` on a clean
 * device is green and meaningless rather than red and confusing.
 *
 * Installing the model — see `scripts/retouch/README.md`:
 * ```
 * adb push ~/.cache/vibe-retouch/acne_640_fp32.onnx /data/local/tmp/blemish-detector/
 * adb push ~/.cache/vibe-retouch/acne_640_fp32.manifest.json \
 *     /data/local/tmp/blemish-detector/detector.manifest.json
 * ./gradlew :core:ai:connectedDebugAndroidTest
 * ```
 * [installFromStaging] copies those two files into the app's private directory, which is where
 * the adapter reads from: nothing is downloaded, nothing comes out of `assets`, and the staging
 * directory is only consulted by this harness.
 *
 * The measurements it prints are tagged [TAG] in logcat, one `key=value` line per figure.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AcneDetectorDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Default
        override val io: CoroutineDispatcher get() = Dispatchers.IO
    }

    private lateinit var store: DetectorModelStore
    private var detector: OnnxBlemishDetector? = null

    @Before
    fun setUp() {
        installFromStaging(context)
        store = DetectorModelStore(context)
    }

    @After
    fun tearDown() {
        // `detector?.close()` is `Unit?`, so this needs a block body to stay `void`.
        runBlocking { detector?.close() }
    }

    @Test
    fun a_the_installed_model_is_the_one_its_manifest_describes() {
        assumeInstalled()

        val model = store.read()
        val contract = model.manifest.contract()

        log("model_bytes", model.manifest.model.bytes)
        log("model_sha256", model.sha256)
        log("detector_version", model.version)
        log("input", "${contract.inputName} [1,3,${contract.inputSize},${contract.inputSize}]")
        log("output", "${contract.outputName} [1,${contract.rows},${contract.anchors}]")
        assertEquals(4 + contract.numClasses, contract.rows)
    }

    /** The whole point of the port: a ROI in, boxes and a mask out, entirely on the device. */
    @Test
    fun b_a_roi_produces_boxes_inside_it_and_a_mask_inside_the_allowance() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val allowed = allowance(ROI_SIZE, ROI_SIZE) { x, y ->
            // A deliberately partial allowance: the top strip stands in for the eyes and brows a
            // real allowed-skin mask excludes, so the subset property is actually exercised.
            y >= ROI_SIZE / 4 && x >= ROI_SIZE / 8
        }

        val result = detector().detect(roi)

        val detections = (result as Result.Success).value
        log("detections", detections.detections.size)
        log("hit_detection_limit", detections.hitDetectionLimit)
        for (detection in detections.detections) {
            assertTrue(
                "box ${detection.box} escaped the ROI",
                detection.box.left >= 0f && detection.box.top >= 0f &&
                    detection.box.right <= ROI_SIZE.toFloat() && detection.box.bottom <= ROI_SIZE.toFloat(),
            )
            assertTrue("degenerate box ${detection.box}", detection.box.width() > 0f && detection.box.height() > 0f)
            assertEquals(0, detection.classId)
        }

        val mask = candidateMask(detections.detections, allowed, roi)
        val maskBytes = mask.alphaBytes()
        val allowedBytes = allowed.alphaBytes()
        assertTrue(maskBytes.all { it == 0.toByte() || it == MASK_ON })
        for (index in maskBytes.indices) {
            if (maskBytes[index] != 0.toByte()) {
                assertTrue("candidate mask escaped the allowance at $index", allowedBytes[index] != 0.toByte())
            }
        }
        log("candidate_mask_pixels", maskBytes.count { it != 0.toByte() })
        log("allowance_pixels", allowedBytes.count { it != 0.toByte() })
    }

    /** requirement 4: the ROI belongs to the caller and comes back untouched. */
    @Test
    fun c_the_input_roi_is_not_modified() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val before = IntArray(ROI_SIZE * ROI_SIZE).also { roi.getPixels(it, 0, ROI_SIZE, 0, 0, ROI_SIZE, ROI_SIZE) }

        detector().detect(roi)

        val after = IntArray(ROI_SIZE * ROI_SIZE).also { roi.getPixels(it, 0, ROI_SIZE, 0, 0, ROI_SIZE, ROI_SIZE) }
        assertTrue(before.contentEquals(after))
    }

    @Test
    fun d_a_non_square_roi_is_handled_and_boxes_stay_inside_it() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(301, 173)

        val detections = (detector().detect(roi) as Result.Success).value

        for (detection in detections.detections) {
            assertTrue(detection.box.right <= 301f && detection.box.bottom <= 173f)
        }
        log("non_square_detections", detections.detections.size)
    }

    /**
     * requirement 9: the session is reused, repeated and concurrent requests are safe, and the
     * shared input buffer is never two runs' at once. Failing this looks like a crash in native
     * code or boxes from the wrong picture, not like a wrong number.
     */
    @Test
    fun e_repeated_and_concurrent_requests_agree() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val detector = detector()

        val first = (detector.detect(roi) as Result.Success).value.detections
        val repeated = (detector.detect(roi) as Result.Success).value.detections
        assertEquals(first.map { it.box }, repeated.map { it.box })

        val concurrent = coroutineScope {
            List(CONCURRENT_REQUESTS) { async { detector.detect(roi) } }.awaitAll()
        }
        for (result in concurrent) {
            assertEquals(first.map { it.box }, (result as Result.Success).value.detections.map { it.box })
        }
    }

    /**
     * requirement 9: a native run cannot be interrupted, so cancellation is re-checked when it
     * returns. What must never happen is a cancelled request delivering a result.
     *
     * Cancelling immediately would only prove that a request cancelled **before** the run started
     * delivers nothing, which is the easy half. [entered] is signalled on the inference thread
     * just before the native call, so the cancel here lands while the model is actually running —
     * the case where the re-check after the call is the only thing standing between a cancelled
     * request and a result.
     */
    @Test
    fun f_a_cancelled_request_delivers_nothing() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val entered = CountDownLatch(1)
        val detector = detector { entered.countDown() }
        var delivered: Result<BlemishDetections>? = null

        val job = launch(Dispatchers.Default) { delivered = detector.detect(roi) }
        assertTrue("the run never started", entered.await(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        job.cancel()
        job.join()

        assertTrue("a cancelled request delivered $delivered", delivered == null)
        // And the detector is still usable afterwards: cancelling one request does not close the
        // session or leave the input buffer half-written.
        assertTrue(detector.detect(roi) is Result.Success)
    }

    /**
     * requirement 9: closing waits for the run in flight rather than freeing under it.
     *
     * Same synchronisation as above, and for the same reason: `delay(1)` before `close()` does not
     * establish that a native run had started, so a green result would say nothing about closing
     * *during* one. Here `close()` is entered while the model is running, and what is asserted is
     * that it waits — the run still returns a result, and only the request after it is refused.
     */
    @Test
    fun g_closing_during_a_run_is_safe_and_then_reports_unavailable() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val entered = CountDownLatch(1)
        val detector = detector { entered.countDown() }

        coroutineScope {
            val running = async(Dispatchers.Default) { detector.detect(roi) }
            assertTrue("the run never started", entered.await(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            detector.close()
            assertNotNull(running.await())
        }

        assertEquals(Result.Failure(AppError.Unavailable), detector.detect(roi))
        this@AcneDetectorDeviceTest.detector = null
    }

    /**
     * requirements 5 and 11: transparent pixels take the fixed background rather than whatever
     * happens to be in the colour channels, and the candidate mask excludes them either way.
     */
    @Test
    fun h_a_roi_with_transparent_pixels_is_handled_and_excluded_from_the_mask() = runBlocking {
        assumeInstalled()
        // `Bitmap.createBitmap(pixels, ...)` returns an **immutable** bitmap — which is what
        // the contract wants the ROI to be — so the hole is punched in the array, not with
        // `setPixel` afterwards.
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE, transparentRows = ROI_SIZE / 2)

        val detections = (detector().detect(roi) as Result.Success).value

        val mask = candidateMask(detections.detections, allowance(ROI_SIZE, ROI_SIZE) { _, _ -> true }, roi)
        val bytes = mask.alphaBytes()
        for (y in 0 until ROI_SIZE / 2) {
            for (x in 0 until ROI_SIZE) {
                assertEquals("transparent pixel ($x,$y) is in the mask", 0.toByte(), bytes[y * ROI_SIZE + x])
            }
        }
    }

    /**
     * requirement 11: cost, on this device, with the stages apart and only successful runs in it.
     *
     * Three things this deliberately does **not** do, each of which would produce a number that
     * reads like a measurement and is not one:
     *
     * * **A failed run's cost is not a detection's cost.** Every result is asserted to be a
     *   `Success` before its time is aggregated, so a run that is failing fast cannot be recorded
     *   as a fast detection.
     * * **Model load, preprocess, inference, decode/NMS and mask are separate figures.** "2 s per
     *   face" says nothing about whether quantisation, a smaller checkpoint or an accelerator is
     *   the thing to try; the split does. The mask is timed here rather than inside the detector
     *   because it is a separate pure transform on the caller's side.
     * * **Current PSS is not peak PSS.** `getProcessMemoryInfo` reports the process *now*, so
     *   reading it after `close()` reports a session that has already been freed. Memory is
     *   sampled on a background thread **during** the runs and the largest sample is reported as
     *   an observed peak — with the resting figure beside it, and the sampling interval, because
     *   a poll can miss a spike between two samples. It is a lower bound on the true peak, and
     *   `work/retouch_evaluation.md` records it as one.
     */
    @Test
    fun i_cost_on_this_device() = runBlocking {
        assumeInstalled()
        val roi = syntheticFace(ROI_SIZE, ROI_SIZE)
        val allowed = allowance(ROI_SIZE, ROI_SIZE) { _, _ -> true }
        val restingPssKb = currentPssKb()

        val coldDetector = OnnxBlemishDetector(store, dispatchers)
        val sampler = PssSampler(::currentPssKb)
        try {
            sampler.start()
            // The first call pays for the session; `loadMs` is the adapter's own measurement of
            // just that, so the rest of this figure is one ordinary detection.
            val coldMs = measureNanoTime {
                assertTrue(
                    "the cold run failed; a failure's cost is not a detection's cost",
                    withTimeout(RUN_TIMEOUT_MS) { coldDetector.detect(roi) } is Result.Success,
                )
            } / NANOS_PER_MILLI

            val totals = ArrayList<Double>(REPEATS)
            val preprocess = ArrayList<Double>(REPEATS)
            val inference = ArrayList<Double>(REPEATS)
            val decode = ArrayList<Double>(REPEATS)
            val mask = ArrayList<Double>(REPEATS)
            repeat(REPEATS) { index ->
                var detections: BlemishDetections? = null
                val total = measureNanoTime {
                    val result = withTimeout(RUN_TIMEOUT_MS) { coldDetector.detect(roi) }
                    assertTrue("run $index failed: $result", result is Result.Success)
                    detections = (result as Result.Success).value
                } / NANOS_PER_MILLI
                val stages = checkNotNull(coldDetector.lastTimings) { "run $index recorded no stages" }
                val maskMs = measureNanoTime {
                    candidateMask(detections!!.detections, allowed, roi)
                } / NANOS_PER_MILLI

                totals += total
                preprocess += stages.preprocessMs
                inference += stages.inferenceMs
                decode += stages.decodeMs
                mask += maskMs
            }

            log("device", "${android.os.Build.MODEL} ${android.os.Build.SUPPORTED_ABIS.first()}")
            log("android_sdk", android.os.Build.VERSION.SDK_INT)
            log("roi", "${ROI_SIZE}x$ROI_SIZE")
            log("repeats", REPEATS)
            log("successful_runs", totals.size)
            log("model_load_ms", "%.1f".format(coldDetector.loadMs ?: -1.0))
            log("cold_first_call_ms", "%.1f".format(coldMs))
            logPercentiles("preprocess", preprocess)
            logPercentiles("inference", inference)
            logPercentiles("decode_nms", decode)
            logPercentiles("mask", mask)
            logPercentiles("detect_total", totals)
            logPercentiles("detect_plus_mask", totals.indices.map { totals[it] + mask[it] })
        } finally {
            // Whatever happened above, the sampler thread stops and the session is freed: a
            // failing assertion must not leave a native session alive for the rest of the class.
            sampler.stop()
            coldDetector.close()
        }

        log("resting_total_pss_kb", restingPssKb)
        log("observed_peak_total_pss_kb", sampler.peakKb)
        log("pss_sample_interval_ms", PSS_SAMPLE_INTERVAL_MS)
        log("pss_samples", sampler.samples)
        log("after_close_total_pss_kb", currentPssKb())
        // Recorded, not asserted: what a poll observes is a lower bound on the true peak.
        log("pss_note", "observed peak is a sampled lower bound; a spike between samples is missed")
    }

    private fun logPercentiles(stage: String, values: List<Double>) {
        val sorted = values.sorted()
        log("${stage}_ms_p50", "%.1f".format(sorted[sorted.size / 2]))
        log("${stage}_ms_p95", "%.1f".format(sorted[((sorted.size - 1) * P95) / PERCENT]))
        log("${stage}_ms_min", "%.1f".format(sorted.first()))
        log("${stage}_ms_max", "%.1f".format(sorted.last()))
    }

    private fun detector(onRunEntered: () -> Unit = {}): OnnxBlemishDetector =
        detector ?: OnnxBlemishDetector(store, dispatchers, onRunEntered = onRunEntered)
            .also { detector = it }

    private fun assumeInstalled() {
        assumeTrue(
            "no detector model installed; see scripts/retouch/README.md",
            store.isInstalled(),
        )
    }

    /**
     * The process's total PSS **right now**. Named for what it is: `getProcessMemoryInfo` has no
     * high-water mark, so a single call after the work is over measures the process at rest.
     */
    private fun currentPssKb(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info: Debug.MemoryInfo =
            manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).first()
        return info.totalPss
    }

    /**
     * A face-shaped test image: a skin-toned oval with darker spots on it. It is **not** a face
     * and no number here is a quality figure — it exists so the path can be exercised with the
     * repository's own pixels. Quality is `detect_eval.py` on the licensed photo set (§2).
     */
    private fun syntheticFace(width: Int, height: Int, transparentRows: Int = 0): Bitmap {
        val pixels = IntArray(width * height)
        val centreX = width / 2f
        val centreY = height / 2f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - centreX) / (width * 0.42f)
                val dy = (y - centreY) / (height * 0.5f)
                val onFace = dx * dx + dy * dy <= 1f
                val grain = ((x * 7 + y * 11) % 13) - 6
                val spot = ((x / 17 + y / 19) % 5 == 0) && ((x % 17) in 6..10) && ((y % 19) in 7..11)
                val base = if (onFace) 205 else 60
                val shade = if (spot && onFace) -55 else 0
                pixels[y * width + x] = Color.argb(
                    if (y < transparentRows) 0 else 255,
                    (base + grain + shade).coerceIn(0, 255),
                    (base - 35 + grain + shade).coerceIn(0, 255),
                    (base - 60 + grain + shade / 2).coerceIn(0, 255),
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * `ALPHA_8` rows are padded to the bitmap's own stride, which is not `width` unless the width
     * happens to be aligned, and `copyPixelsFrom/ToBuffer` move the padding too. Both helpers work
     * in unpadded `width * height` coordinates and convert at the bitmap boundary — the same rule
     * `CandidateMask.kt` follows, and the same mistake that made it throw on an odd-width ROI.
     */
    private fun allowance(width: Int, height: Int, allowed: (Int, Int) -> Boolean): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val bytes = ByteArray(mask.rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) bytes[y * mask.rowBytes + x] = if (allowed(x, y)) MASK_ON else 0
        }
        return mask.apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
    }

    /** Returns unpadded `width * height` order, whatever the bitmap's stride is. */
    private fun Bitmap.alphaBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(byteCount)
        copyPixelsToBuffer(buffer)
        val padded = buffer.array()
        val out = ByteArray(width * height)
        for (y in 0 until height) padded.copyInto(out, y * width, y * rowBytes, y * rowBytes + width)
        return out
    }

    /**
     * A block body, not `= Log.i(...)`: `Log.i` returns an `Int`, and a test method whose
     * expression body ends in this would not be `void`, which JUnit 4 rejects for the whole class.
     */
    private fun log(key: String, value: Any) {
        Log.i(TAG, "$key=$value")
    }

    private companion object {
        const val TAG = "AcneDetectorDevice"
        const val ROI_SIZE = 512
        const val REPEATS = 20
        const val CONCURRENT_REQUESTS = 4
        const val RUN_TIMEOUT_MS = 60_000L
        const val MASK_ON = 255.toByte()
        const val NANOS_PER_MILLI = 1_000_000.0
        const val PSS_SAMPLE_INTERVAL_MS = 100L
        const val P95 = 95
        const val PERCENT = 100
    }
}

/**
 * Polls the process's PSS on a background thread and keeps the largest reading.
 *
 * `Debug.MemoryInfo` has no high-water mark, so the only way to say anything about the peak is to
 * look while the work is happening. What this reports is therefore an **observed** peak: a lower
 * bound, since a spike shorter than the interval is missed entirely. That limit is logged beside
 * the figure rather than left for a reader to assume away.
 */
private class PssSampler(private val read: () -> Int) {
    @Volatile private var running = false
    @Volatile var peakKb: Int = 0
        private set

    @Volatile var samples: Int = 0
        private set

    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            while (running) {
                val value = runCatching(read).getOrDefault(0)
                if (value > peakKb) peakKb = value
                samples++
                try {
                    Thread.sleep(PSS_SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(SAMPLER_JOIN_MS)
        thread = null
    }

    private companion object {
        const val PSS_SAMPLE_INTERVAL_MS = 100L
        const val SAMPLER_JOIN_MS = 1_000L
    }
}

/**
 * Copies a model staged with `adb push` into the app's private directory.
 *
 * `work/tasks.md` requirement 8 wants the adapter to read an **explicitly installed** model out of
 * app-private files. `/data/local/tmp` is where `adb push` can write without root, and this is the
 * explicit installation step: it runs only from this instrumentation test, it copies exactly two
 * named files, and it downloads nothing.
 *
 * The staging directory is not listed, only opened by name: `/data/local/tmp` is traversable but
 * not readable by an app uid, so a directory listing there returns nothing even when the files
 * are perfectly readable.
 */
private fun installFromStaging(context: Context) {
    val staging = File(STAGING_DIRECTORY)
    val manifest = File(staging, "detector.manifest.json")
    if (!manifest.canRead()) return
    val target = File(context.filesDir, "blemish-detector").apply { mkdirs() }
    val installedManifest = manifest.copyInto(File(target, "detector.manifest.json")) ?: return

    // Parsed, not pattern-matched: the manifest has several `"file"` keys and the provenance
    // block's is the checkpoint's name, not the ONNX's.
    val modelName = JSONObject(installedManifest.readText()).getJSONObject("model").getString("file")
    File(staging, modelName).copyInto(File(target, modelName))
}

/** Copies when the destination is absent or a different length; returns the destination. */
private fun File.copyInto(destination: File): File? {
    if (!canRead()) return null
    if (!destination.exists() || destination.length() != length()) {
        inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    }
    return destination
}

private const val STAGING_DIRECTORY = "/data/local/tmp/blemish-detector"
