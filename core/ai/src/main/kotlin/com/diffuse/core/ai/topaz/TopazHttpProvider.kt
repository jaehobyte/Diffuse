package com.diffuse.core.ai.topaz

import android.graphics.BitmapFactory
import com.diffuse.core.common.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * specs/topaz_enhancement.md §4. How long to wait and how often to ask. A seam so tests run in
 * milliseconds; the defaults are the production values.
 */
internal data class TopazPolling(
    val firstDelayMs: Long = FIRST_DELAY_MS,
    val maxDelayMs: Long = MAX_DELAY_MS,
    /** The FAQ's longest stated processing time is ~90 minutes; a phone screen waits less. */
    val deadlineMs: Long = DEADLINE_MS,
    /** Consecutive transient failures of an idempotent call before giving up. */
    val maxTransientFailures: Int = MAX_TRANSIENT_FAILURES,
    val cancelTimeoutMs: Long = CANCEL_TIMEOUT_MS,
    val clock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val MAX_DELAY_MS = 10_000L
        const val DEADLINE_MS = 30 * 60_000L
        const val MAX_TRANSIENT_FAILURES = 5
        const val CANCEL_TIMEOUT_MS = 15_000L
    }
}

/**
 * specs/topaz_enhancement.md §4. Submit once, poll with backoff, fetch the signed URL, stream the
 * file, check it is the image that was asked for. Only the idempotent calls are retried.
 */
@Singleton
class TopazHttpProvider internal constructor(
    override val config: StateFlow<TopazConfig>,
    private val client: TopazClient,
    private val dispatchers: DispatcherProvider,
    private val polling: TopazPolling,
) : TopazProvider {

    @Inject
    internal constructor(
        settings: TopazSettings,
        client: TopazClient,
        dispatchers: DispatcherProvider,
    ) : this(settings.config, client, dispatchers, TopazPolling())

    /**
     * Where a best-effort remote cancel runs once its caller is gone. Each launch is bounded by
     * [TopazPolling.cancelTimeoutMs]; nothing here outlives that. Not `GlobalScope` (§4).
     */
    private val cleanup = CoroutineScope(SupervisorJob() + dispatchers.io)

    override suspend fun estimate(job: TopazJob): TopazOutcome<TopazEstimate> {
        val config = this.config.value
        val refusal = if (config.isConfigured) job.problem() else TopazFailure.NotConfigured
        if (refusal != null) return TopazOutcome.Failure(refusal)
        return when (val outcome = client.estimate(config, job.purpose.endpoint, job.estimateFields())) {
            is TopazOutcome.Success -> TopazOutcome.Success(
                TopazEstimate(outcome.value.credits, outcome.value.duration),
            )
            is TopazOutcome.Failure -> outcome
        }
    }

    // Guard clauses, then the three stages each with its own way out.
    @Suppress("ReturnCount")
    override suspend fun enhance(
        job: TopazJob,
        input: File,
        output: File,
        onStage: (TopazStage) -> Unit,
    ): TopazOutcome<TopazOutput> {
        val config = this.config.value
        val refusal = if (config.isConfigured) job.problem() else TopazFailure.NotConfigured
        if (refusal != null) return TopazOutcome.Failure(refusal)

        onStage(TopazStage.Uploading)
        val processId = when (
            val submitted = client.submit(config, job.purpose.endpoint, job.formFields(), input, INPUT_MIME_TYPE)
        ) {
            is TopazOutcome.Success -> submitted.value
            is TopazOutcome.Failure -> return submitted
        }
        onStage(TopazStage.Submitted(processId))

        var settled = false
        try {
            val status = poll(config, processId, onStage)
            settled = true
            if (status is TopazOutcome.Failure) {
                // Topaz ended these itself; anything else may still be running, and billing.
                if (status.failure != TopazFailure.JobFailed && status.failure != TopazFailure.JobCancelled) {
                    cancelRemotely(config, processId)
                }
                return status
            }
            onStage(TopazStage.Downloading)
            val credits = (status as TopazOutcome.Success).value.credits
            return fetch(config, processId, job, output, credits)
        } catch (e: CancellationException) {
            if (!settled) cancelRemotely(config, processId)
            partialOf(output).delete()
            throw e
        }
    }

    /** Ends in Completed (success) or a failure. An undocumented status is never "done". */
    // A state machine: each documented terminal status is its own return.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun poll(
        config: TopazConfig,
        processId: String,
        onStage: (TopazStage) -> Unit,
    ): TopazOutcome<StatusDto> {
        val deadline = polling.clock() + polling.deadlineMs
        var wait = polling.firstDelayMs
        var transient = 0
        while (true) {
            if (polling.clock() > deadline) return TopazOutcome.Failure(TopazFailure.Timeout)
            delay(wait)
            wait = min((wait * BACKOFF).roundToLong(), polling.maxDelayMs)
            when (val outcome = client.status(config, processId)) {
                is TopazOutcome.Failure -> {
                    if (!outcome.failure.isTransient() || ++transient > polling.maxTransientFailures) {
                        return outcome
                    }
                    outcome.retryAfterMs?.let { wait = maxOf(wait, it) }
                }
                is TopazOutcome.Success -> {
                    transient = 0
                    when (outcome.value.status) {
                        TopazStatus.PENDING -> onStage(TopazStage.Queued)
                        TopazStatus.PROCESSING ->
                            onStage(TopazStage.Processing(outcome.value.progress?.takeIf { it in 1..PERCENT }))
                        TopazStatus.COMPLETED -> return outcome
                        TopazStatus.FAILED -> return TopazOutcome.Failure(TopazFailure.JobFailed)
                        TopazStatus.CANCELLED -> return TopazOutcome.Failure(TopazFailure.JobCancelled)
                        else -> return TopazOutcome.Failure(TopazFailure.BadResponse)
                    }
                }
            }
        }
    }

    /** The signed URL, then the file; both retried as transient, neither re-submits. */
    private suspend fun fetch(
        config: TopazConfig,
        processId: String,
        job: TopazJob,
        output: File,
        credits: Int?,
    ): TopazOutcome<TopazOutput> {
        val partial = partialOf(output)
        var attempts = 0
        while (true) {
            val outcome = downloadOnce(config, processId, partial)
            if (outcome is TopazOutcome.Success) break
            outcome as TopazOutcome.Failure
            partial.delete()
            if (!outcome.failure.isTransient() || ++attempts > polling.maxTransientFailures) return outcome
            delay(outcome.retryAfterMs ?: polling.firstDelayMs)
        }
        return withContext(dispatchers.io) {
            val checked = validate(partial, job, credits)
            if (checked is TopazOutcome.Success && partial.renameTo(output)) {
                TopazOutcome.Success(checked.value.copy(file = output))
            } else {
                partial.delete()
                checked as? TopazOutcome.Failure ?: TopazOutcome.Failure(TopazFailure.DiskFull)
            }
        }
    }

    private suspend fun downloadOnce(config: TopazConfig, processId: String, target: File): TopazOutcome<Long> =
        when (val url = client.downloadUrl(config, processId)) {
            is TopazOutcome.Failure -> url
            is TopazOutcome.Success -> client.download(url.value, target, MAX_OUTPUT_BYTES)
        }

    /**
     * specs/topaz_enhancement.md §5: bounds only — the whole file is never decoded. The type is
     * the one asked for, and the size is the one asked for (±1px for the proportional side).
     */
    private fun validate(file: File, job: TopazJob, credits: Int?): TopazOutcome<TopazOutput> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val valid = bounds.outMimeType == TopazJob.OUTPUT_MIME_TYPE &&
            bounds.outWidth == job.resultWidth &&
            abs(bounds.outHeight - job.resultHeight) <= 1
        return if (valid) {
            TopazOutcome.Success(
                TopazOutput(file, bounds.outWidth, bounds.outHeight, TopazJob.OUTPUT_MIME_TYPE, file.length(), credits),
            )
        } else {
            TopazOutcome.Failure(TopazFailure.OutputInvalid)
        }
    }

    private fun partialOf(output: File) = File(output.parentFile, output.name + PARTIAL_SUFFIX)

    /** §4: best effort, under the key the run started with, bounded in time. */
    private fun cancelRemotely(config: TopazConfig, processId: String) {
        cleanup.launch { withTimeoutOrNull(polling.cancelTimeoutMs) { client.cancel(config, processId) } }
    }

    private fun TopazFailure.isTransient(): Boolean = when (this) {
        TopazFailure.RateLimited, TopazFailure.Server, TopazFailure.Timeout, TopazFailure.Offline -> true
        else -> false
    }

    companion object {
        /** The input is always a JPEG of the rendered document (§5). */
        const val INPUT_MIME_TYPE = "image/jpeg"

        /** §5's byte ceiling: a 256MP JPEG at high quality stays well under this. */
        const val MAX_OUTPUT_BYTES = 400L * 1024 * 1024

        private const val BACKOFF = 1.5
        private const val PERCENT = 100
        private const val PARTIAL_SUFFIX = ".part"
    }
}
