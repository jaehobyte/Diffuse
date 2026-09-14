package com.diffuse.core.ai.retouch

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * `work/tasks.md` requirement 8. The exported acne detector on ONNX Runtime, **for evaluation**.
 *
 * Debug-only on purpose, and in three ways: the runtime is a `debugImplementation` dependency so
 * no release APK carries it, the model is a file a developer pushes into the app's private
 * directory rather than an asset or a download, and nothing in `AiModule` binds this. Adopting a
 * runtime for production is a later decision, and specs/skin_retouch_pipeline.md §1.1 says it
 * needs the on-device latency, memory and size numbers this class exists to produce.
 *
 * Ownership, which requirement 9 asks to be explicit about:
 * - the `OrtEnvironment` is process-wide and shared, so it is **never** closed here;
 * - the session is created once, reused, and closed by [close];
 * - every tensor is closed on both the success and the failure path;
 * - `faceRoi` belongs to the caller and is only read.
 *
 * Runs are serialised on [runLock]. One face at a time is what the sheet asks for anyway, and it
 * is what makes reusing the input buffer safe and what stops [close] from freeing a session out
 * from under a running inference.
 */
internal class OnnxBlemishDetector(
    private val store: DetectorModelStore,
    private val dispatchers: DispatcherProvider,
    private val settings: DetectorSettings = DetectorSettings(),
    /**
     * Called on the inference thread immediately before the native `run`, and nowhere else.
     *
     * A test seam, and the only way to be sure of the thing requirement 9 is actually about:
     * `delay(1)` before a cancel or a `close` does not establish that the run has *started*, so a
     * green test proves nothing about interrupting one. The device harness passes a latch here.
     * Default is a no-op and no production code constructs this class at all.
     */
    private val onRunEntered: () -> Unit = {},
) : BlemishDetector {

    private val runLock = Mutex()
    private var loaded: Loaded? = null
    private var closed = false

    /**
     * Stage costs of the most recent **successful** run, and the session load, for
     * `work/tasks.md` requirement 11. `null` until one has completed.
     *
     * Only written by a run holding [runLock], so a reader between runs sees a whole record.
     * A failed or cancelled run leaves the previous one in place rather than recording the cost
     * of not working — the harness aggregates successes only.
     */
    @Volatile
    var lastTimings: DetectorTimings? = null
        private set

    /** Milliseconds spent creating the session, once per instance. `null` until it is loaded. */
    @Volatile
    var loadMs: Double? = null
        private set

    override val detectorVersion: String
        get() = loaded?.model?.version ?: NOT_LOADED_VERSION

    override suspend fun detect(faceRoi: Bitmap): Result<BlemishDetections> {
        val session = when (val prepared = prepared()) {
            is Result.Failure -> return Result.Failure(prepared.error)
            is Result.Success -> prepared.value
        }
        return runLock.withLock {
            if (closed) return@withLock Result.Failure(AppError.Unavailable)
            runOnce(session, faceRoi)
        }
    }

    /**
     * Reading and hashing the model is file work, so it happens on `io` — and once: a second
     * caller waits on the same lock and finds the session already there.
     */
    private suspend fun prepared(): Result<Loaded> {
        loaded?.let { return Result.Success(it) }
        return runLock.withLock {
            if (closed) return@withLock Result.Failure(AppError.Unavailable)
            loaded?.let { return@withLock Result.Success(it) }
            withContext(dispatchers.io) {
                try {
                    val model = store.read()
                    val contract = model.manifest.contract()
                    val options = OrtSession.SessionOptions()
                    val startedLoad = System.nanoTime()
                    val session = try {
                        OrtEnvironment.getEnvironment().createSession(model.file.absolutePath, options)
                    } catch (error: Throwable) {
                        // The options object owns native memory of its own, so it is released
                        // whether or not a session was ever built out of it.
                        options.close()
                        throw error
                    }
                    val onGraph = try {
                        session.graphContract(model.manifest.decode.numClasses)
                    } catch (error: Throwable) {
                        session.close()
                        options.close()
                        throw error
                    }
                    if (onGraph != contract) {
                        session.close()
                        options.close()
                        throw ModelCorrupt("graph says $onGraph, manifest says $contract")
                    }
                    loadMs = elapsedMs(startedLoad)
                    Loaded(model, session, options, contract, FloatArray(3 * contract.inputSize * contract.inputSize))
                        .also { loaded = it }
                        .let { Result.Success(it) }
                } catch (_: ModelNotInstalled) {
                    // Not installed is not broken: the app simply has no detector in this build.
                    Result.Failure(AppError.Unavailable)
                } catch (corrupt: ModelCorrupt) {
                    Result.Failure(AppError.Invalid(corrupt.message.orEmpty()))
                } catch (io: IOException) {
                    Result.Failure(AppError.Io(io))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    // Class name only. specs/skin_retouch_pipeline.md §2: no photograph and no
                    // raw tensor reaches a log or an error string.
                    Result.Failure(AppError.Invalid("model load failed: ${error.javaClass.simpleName}"))
                }
            }
        }
    }

    private suspend fun runOnce(loaded: Loaded, faceRoi: Bitmap): Result<BlemishDetections> =
        withContext(dispatchers.default) {
            val contract = loaded.contract
            val shape = longArrayOf(1, 3, contract.inputSize.toLong(), contract.inputSize.toLong())
            val environment = OrtEnvironment.getEnvironment()
            try {
                // Reading the ROI is inside the guard too: a bitmap the caller has recycled
                // between the request and the run is a reportable failure, not a crash.
                val startedPreprocess = System.nanoTime()
                val (buffer, transform) = preprocess(faceRoi, contract.inputSize, loaded.input)
                val preprocessMs = elapsedMs(startedPreprocess)
                coroutineContext.ensureActive()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(buffer), shape).use { tensor ->
                    onRunEntered()
                    val startedInference = System.nanoTime()
                    loaded.session.run(mapOf(contract.inputName to tensor)).use { results ->
                        val inferenceMs = elapsedMs(startedInference)
                        // The native call does not observe cancellation, so it is re-checked the
                        // moment it returns: a cancelled request must not produce a result
                        // (requirement 9). `ensureActive` throws, which propagates.
                        coroutineContext.ensureActive()
                        val startedDecode = System.nanoTime()
                        val output = results.get(0).value
                        val raw = flatten(output, contract)
                            ?: return@withContext Result.Failure(
                                AppError.Invalid("unexpected output shape from ${contract.outputName}"),
                            )
                        val (detections, hitLimit) = decodeDetections(
                            raw,
                            contract,
                            transform,
                            settings.copy(acneClassIds = loaded.model.settingsClassIds),
                        )
                        val decodeMs = elapsedMs(startedDecode)
                        // Recorded only here, on the success path: a failed or cancelled run's
                        // cost is not the cost of a detection (requirement 11).
                        lastTimings = DetectorTimings(preprocessMs, inferenceMs, decodeMs)
                        Result.Success(
                            BlemishDetections(detections, loaded.model.version, hitLimit),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.Failure(AppError.Invalid("inference failed: ${error.javaClass.simpleName}"))
            }
        }

    /**
     * Closes the session. Suspends for a run in flight rather than freeing native memory
     * underneath it, and is safe to call twice. The shared environment is left alone: it belongs
     * to the process, and closing it per request would tear down every other user of it
     * (requirement 9). After this, [detect] reports `Unavailable` rather than reloading.
     */
    suspend fun close() {
        runLock.withLock {
            closed = true
            loaded?.let {
                it.session.close()
                it.options.close()
            }
            loaded = null
        }
    }

    private class Loaded(
        val model: InstalledModel,
        val session: OrtSession,
        val options: OrtSession.SessionOptions,
        val contract: DetectorContract,
        /** Reused across runs; safe because [runLock] serialises them. */
        val input: FloatArray,
    )

    private companion object {
        const val NOT_LOADED_VERSION = "acne-yolov8m-onnx@unloaded"
    }
}

/**
 * One successful detection's cost, by stage. `work/tasks.md` requirement 11 asks for the stages
 * apart rather than one number, because "2 s per face" says nothing about whether quantisation or
 * a smaller checkpoint is the thing to try next.
 *
 * The candidate mask is **not** in here: it is a separate pure transform on the caller's side of
 * the detector, and the harness times it separately.
 */
internal data class DetectorTimings(
    val preprocessMs: Double,
    val inferenceMs: Double,
    val decodeMs: Double,
) {
    val totalMs: Double get() = preprocessMs + inferenceMs + decodeMs
}

private const val NANOS_PER_MILLI = 1_000_000.0

private fun elapsedMs(since: Long): Double = (System.nanoTime() - since) / NANOS_PER_MILLI

/**
 * The graph's own view, so a manifest can be checked against the file rather than trusted.
 *
 * The batch and channel axes are compared as well as the spatial ones: `preprocess` writes one
 * `[1,3,S,S]` tensor, and a graph taking a batch of four or a single channel would be fed a buffer
 * of the wrong length or the wrong meaning. Element types are compared for the same reason — this
 * writes float32 and reads float32, so an FP16 export of identical shape is a different graph
 * (`work/tasks.md` requirement 2).
 */
private fun OrtSession.graphContract(numClasses: Int): DetectorContract {
    val input = inputInfo.entries.singleOrNull() ?: throw ModelCorrupt("expected one input, got ${inputInfo.size}")
    val output = outputInfo.entries.singleOrNull() ?: throw ModelCorrupt("expected one output, got ${outputInfo.size}")
    val inputInfo = (input.value.info as? ai.onnxruntime.TensorInfo)
        ?: throw ModelCorrupt("input ${input.key} is not a tensor")
    val outputInfo = (output.value.info as? ai.onnxruntime.TensorInfo)
        ?: throw ModelCorrupt("output ${output.key} is not a tensor")
    val inputShape = inputInfo.shape
    val outputShape = outputInfo.shape
    if (inputShape.size != 4 || inputShape[0] != 1L || inputShape[1] != 3L || inputShape[2] != inputShape[3]) {
        throw ModelCorrupt("expected a square [1,3,S,S] input, got ${inputShape.toList()}")
    }
    if (outputShape.size != 3 || outputShape[0] != 1L) {
        throw ModelCorrupt("expected a [1,rows,anchors] output, got ${outputShape.toList()}")
    }
    for (tensor in listOf(input.key to inputInfo, output.key to outputInfo)) {
        if (tensor.second.type != OnnxJavaType.FLOAT) {
            throw ModelCorrupt("${tensor.first} is ${tensor.second.type}, this build reads ${OnnxJavaType.FLOAT}")
        }
    }
    return runCatching {
        DetectorContract(
            inputName = input.key,
            inputSize = inputShape[2].toInt(),
            outputName = output.key,
            rows = outputShape[1].toInt(),
            anchors = outputShape[2].toInt(),
            numClasses = numClasses,
        )
    }.getOrElse { throw ModelCorrupt(it.message ?: "unusable graph shape") }
}

/**
 * `[1][rows][anchors]` to the flat row-major array the decoder reads, or `null` if the runtime
 * handed back something else. Null rather than an exception, so a wrong shape becomes a reported
 * `Invalid` instead of a crash inside `use`.
 */
private fun flatten(output: Any?, contract: DetectorContract): FloatArray? {
    @Suppress("UNCHECKED_CAST")
    val batch = (output as? Array<*>)?.singleOrNull() as? Array<FloatArray> ?: return null
    if (batch.size != contract.rows) return null
    val flat = FloatArray(contract.rows * contract.anchors)
    for (row in 0 until contract.rows) {
        val values = batch[row]
        if (values.size != contract.anchors) return null
        values.copyInto(flat, row * contract.anchors)
    }
    return flat
}
