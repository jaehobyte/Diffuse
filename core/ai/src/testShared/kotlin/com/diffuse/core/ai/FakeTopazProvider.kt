package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.diffuse.core.ai.topaz.TopazConfig
import com.diffuse.core.ai.topaz.TopazEstimate
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazJob
import com.diffuse.core.ai.topaz.TopazOutcome
import com.diffuse.core.ai.topaz.TopazOutput
import com.diffuse.core.ai.topaz.TopazProvider
import com.diffuse.core.ai.topaz.TopazStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * specs/topaz_enhancement.md §8. Writes a real JPEG of the size the job asks for, so the editor's
 * bounds checks, compare decode and file copies run against a file rather than a promise.
 *
 * [submitCount] is the number the "no paid job without 실행" tests count.
 */
class FakeTopazProvider(apiKey: String = "topaz-test-key") : TopazProvider {

    private val _config = MutableStateFlow(TopazConfig(apiKey = apiKey))
    override val config: StateFlow<TopazConfig> = _config

    var submitCount: Int = 0
        private set

    var estimateCount: Int = 0
        private set

    var cancelledCount: Int = 0
        private set

    var lastJob: TopazJob? = null
        private set

    /** Bytes of the input the last run was handed, so a test can check what was sent. */
    var lastInputBytes: ByteArray? = null
        private set

    /** When set, [enhance] waits on it after submitting — the window a race test needs. */
    var gate: CompletableDeferred<Unit>? = null

    /** A provider that ignores its caller's cancellation and answers anyway once [gate] opens. */
    var ignoreCancel: Boolean = false

    var nextFailure: TopazFailure? = null
    var estimateFailure: TopazFailure? = null
    var credits: Int = 3

    fun setKey(apiKey: String) {
        _config.value = _config.value.copy(apiKey = apiKey, generation = _config.value.generation + 1)
    }

    override suspend fun estimate(job: TopazJob): TopazOutcome<TopazEstimate> {
        estimateCount++
        job.problem()?.let { return TopazOutcome.Failure(it) }
        estimateFailure?.let { return TopazOutcome.Failure(it) }
        return TopazOutcome.Success(TopazEstimate(credits, durationSeconds = 5))
    }

    override suspend fun enhance(
        job: TopazJob,
        input: File,
        output: File,
        onStage: (TopazStage) -> Unit,
    ): TopazOutcome<TopazOutput> {
        if (!_config.value.isConfigured) return TopazOutcome.Failure(TopazFailure.NotConfigured)
        job.problem()?.let { return TopazOutcome.Failure(it) }
        submitCount++
        lastJob = job
        lastInputBytes = input.readBytes()
        onStage(TopazStage.Submitted("fake-$submitCount"))
        try {
            if (ignoreCancel) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate?.await() }
            } else {
                gate?.await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledCount++
            throw e
        }
        nextFailure?.let { nextFailure = null; return TopazOutcome.Failure(it) }
        onStage(TopazStage.Downloading)
        val width = job.resultWidth
        val height = job.resultHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(RESULT_COLOR)
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return TopazOutcome.Success(
            TopazOutput(output, width, height, TopazJob.OUTPUT_MIME_TYPE, output.length(), credits),
        )
    }

    companion object {
        val RESULT_COLOR: Int = Color.rgb(200, 40, 40)
        private const val JPEG_QUALITY = 90
    }
}
