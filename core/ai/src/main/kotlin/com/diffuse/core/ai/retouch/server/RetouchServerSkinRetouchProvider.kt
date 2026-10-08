package com.diffuse.core.ai.retouch.server

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CorrectionOutcome
import com.diffuse.core.ai.ExecutionLocation
import com.diffuse.core.ai.PreparedCorrection
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.SkinRetouchProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * specs/skin_retouch_pipeline.md §8, work/tasks.md requirement 18. The skin retouch server behind
 * the provider boundary: one probe per settings, one upload per explicit 미리보기 request.
 *
 * The probe is `MonetAutoEnhanceProvider`'s, for the same reasons (auto_enhance.md §6, D081): a
 * save re-probes even when nothing changed, a probe for replaced settings is cancelled and its late
 * answer dropped, and nothing polls. What it adds is [supportedKinds]: the server says which kinds
 * it has enabled and which engine version answers for each, and a request names that version so a
 * swapped engine cannot answer for the old one (§8.1 `engine_mismatch`).
 */
@Singleton
class RetouchServerSkinRetouchProvider @Inject internal constructor(
    private val client: RetouchServerClient,
    private val settings: RetouchServerSettings,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : SkinRetouchProvider {

    override val executionLocation: ExecutionLocation = ExecutionLocation.RetouchServer

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val _availability = MutableStateFlow(initialAvailability(settings.current()))
    override val availability: StateFlow<Availability> = _availability.asStateFlow()

    private val _checking = MutableStateFlow(settings.current().isConfigured)
    override val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _supportedKinds = MutableStateFlow<Set<SkinRetouchKind>>(emptySet())
    override val supportedKinds: StateFlow<Set<SkinRetouchKind>> = _supportedKinds.asStateFlow()

    private val lock = Any()

    /** Guarded by [lock]: the probe that owns [_checking], and the engines the last one reported. */
    private var probe: Probe? = null
    private var engines: Map<SkinRetouchKind, String> = emptyMap()
    private var enginesFor: RetouchServerConfig? = null

    private class Probe(val config: RetouchServerConfig, val job: Job)

    init {
        scope.launch { settings.saves.collect { refresh() } }
    }

    override fun refresh() {
        val config = settings.current()
        synchronized(lock) {
            val running = probe
            if (running != null && running.config == config && running.job.isActive) return
            running?.job?.cancel()
            if (!config.isConfigured) {
                probe = null
                publish(config, Availability.Unavailable(AppError.Invalid(NO_SERVER)), emptyMap())
                _checking.value = false
                return
            }
            _checking.value = true
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val answer = client.health(config)
                synchronized(lock) {
                    if (probe?.job !== coroutineContext[Job]) return@launch
                    probe = null
                    when (answer) {
                        is Result.Success -> publish(config, Availability.Ready, answer.value.engines)
                        is Result.Failure -> publish(config, Availability.Unavailable(answer.error), emptyMap())
                    }
                    _checking.value = false
                }
            }
            probe = Probe(config, job)
            job.start()
        }
    }

    /** Called under [lock]. */
    private fun publish(
        config: RetouchServerConfig,
        availability: Availability,
        reported: Map<SkinRetouchKind, String>,
    ) {
        engines = reported
        enginesFor = config
        _availability.value = availability
        _supportedKinds.value = reported.keys
    }

    @Suppress("ReturnCount")
    override suspend fun prepare(
        faceRoi: Bitmap,
        allowedMask: Bitmap,
        kind: SkinRetouchKind,
    ): Result<PreparedCorrection> {
        if (allowedMask.width != faceRoi.width || allowedMask.height != faceRoi.height ||
            allowedMask.config != Bitmap.Config.ALPHA_8
        ) {
            return Result.Failure(AppError.Invalid("allowed mask is not the roi size"))
        }
        val config = settings.current()
        val engineVersion = synchronized(lock) {
            engines[kind].takeIf { enginesFor == config && _availability.value is Availability.Ready }
        } ?: return Result.Failure(
            if (_availability.value is Availability.Ready) AppError.Unsupported else AppError.Unavailable,
        )

        val roi = withContext(dispatchers.default) { wireRoi(faceRoi, allowedMask) }
        // Nothing may change: no upload at all, which is also the contract's own answer (§8.1).
        if (roi.allowed.none { it }) return Result.Success(noChange(faceRoi, engineVersion))
        coroutineContext.ensureActive()

        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        val answer = client.retouch(config, requestId, kind, engineVersion, roi)
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
        return when (answer) {
            is Result.Failure -> {
                logger.warn(
                    TAG,
                    "retouch $requestId ${kind.wireName} failed after ${elapsedMs}ms: ${answer.error.label()}",
                )
                if (answer.error.makesProbeStale()) refresh()
                answer
            }
            is Result.Success -> {
                val correction = answer.value
                logger.debug(
                    TAG,
                    "retouch $requestId ${kind.wireName} ${correction.javaClass.simpleName} in ${elapsedMs}ms",
                )
                Result.Success(withContext(dispatchers.default) { prepared(faceRoi, correction) })
            }
        }
    }

    /** The ROI's pixels and the allowance, already cut to the ROI's own opaque pixels. */
    private fun wireRoi(faceRoi: Bitmap, allowedMask: Bitmap): RetouchServerClient.Roi {
        val width = faceRoi.width
        val height = faceRoi.height
        val argb = IntArray(width * height)
        faceRoi.getPixels(argb, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocate(allowedMask.byteCount)
        allowedMask.copyPixelsToBuffer(buffer)
        val bytes = buffer.array()
        val stride = allowedMask.rowBytes
        val allowed = BooleanArray(width * height) { index ->
            val y = index / width
            bytes[y * stride + index - y * width].toInt() != 0 && argb[index] ushr ALPHA_SHIFT != 0
        }
        return RetouchServerClient.Roi(width, height, argb, allowed)
    }

    private fun prepared(faceRoi: Bitmap, correction: RetouchServerClient.Correction): PreparedCorrection =
        when (correction) {
            is RetouchServerClient.Correction.NoChange -> noChange(faceRoi, correction.engineVersion)
            is RetouchServerClient.Correction.Corrected -> {
                val width = faceRoi.width
                val height = faceRoi.height
                val candidate = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                candidate.setPixels(correction.candidate, 0, width, 0, 0, width, height)
                val support = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val stride = support.rowBytes
                val bytes = ByteArray(stride * height)
                for (i in correction.support.indices) {
                    if (correction.support[i]) bytes[(i / width) * stride + i % width] = ON
                }
                support.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                PreparedCorrection(candidate, support, CorrectionOutcome.Corrected, correction.engineVersion)
            }
        }

    /** A normal answer, with the input as its candidate and nothing in its support. */
    private fun noChange(faceRoi: Bitmap, engineVersion: String) = PreparedCorrection(
        candidate = faceRoi.copy(Bitmap.Config.ARGB_8888, false),
        changeSupport = Bitmap.createBitmap(faceRoi.width, faceRoi.height, Bitmap.Config.ALPHA_8),
        outcome = CorrectionOutcome.NoChange,
        engineVersion = engineVersion,
    )

    private fun AppError.makesProbeStale(): Boolean =
        this is AppError.Io || this == AppError.Unavailable || this == AppError.Unauthorized

    /** For the log: the kind of failure, never a body or a token. */
    private fun AppError.label(): String = when (this) {
        is AppError.Io -> "io:${cause.javaClass.simpleName}"
        is AppError.Invalid -> "invalid:$detail"
        else -> javaClass.simpleName
    }

    private fun initialAvailability(config: RetouchServerConfig): Availability =
        if (config.isConfigured) {
            Availability.Unavailable(AppError.Unavailable)
        } else {
            Availability.Unavailable(AppError.Invalid(NO_SERVER))
        }

    companion object {
        /** `AppError.Invalid.detail` for "no address at all", which the sheet sends to 서버 설정. */
        const val NO_SERVER = RetouchServerClient.NO_SERVER_ADDRESS

        /** `AppError.Invalid.detail` for an address that is set but cannot be requested. */
        const val INVALID_ADDRESS = RetouchServerClient.INVALID_SERVER_ADDRESS

        private const val TAG = "RetouchServer"
        private const val ALPHA_SHIFT = 24
        private const val NANOS_PER_MS = 1_000_000L
        private val ON = 255.toByte()
    }
}
