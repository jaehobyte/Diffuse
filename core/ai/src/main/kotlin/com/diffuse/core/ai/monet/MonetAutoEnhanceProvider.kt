package com.diffuse.core.ai.monet

import android.graphics.Bitmap
import com.diffuse.core.ai.AutoEnhanceProvider
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * specs/auto_enhance.md §5. The whole provider is the client plus one probe: everything that
 * would otherwise be here — the operation table, the value scale, the JSON in the prose — is in
 * `MonetClient`, because §8's tests reach that seam directly.
 */
@Singleton
class MonetAutoEnhanceProvider @Inject internal constructor(
    private val client: MonetClient,
    private val settings: MonetSettings,
    dispatchers: DispatcherProvider,
) : AutoEnhanceProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val _availability = MutableStateFlow(initialAvailability(settings.current()))
    override val availability: StateFlow<Availability> = _availability.asStateFlow()

    /** True from construction when there is something to probe, so no tap reads "unreachable". */
    private val _checking = MutableStateFlow(settings.current().isConfigured)
    override val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val lock = Any()

    /** The probe that owns [_checking]; only it may write an answer. Guarded by [lock]. */
    private var probe: Probe? = null

    private class Probe(val config: MonetConfig, val job: Job)

    init {
        // §4: a probe rather than Gemini's "is a key present" rule — this server is the user's
        // own, so reachability is the real question. §6: every save re-probes, including one
        // that saves the same address again, which is what `saves` counts and `config` cannot.
        scope.launch {
            settings.saves.collect { refresh() }
        }
    }

    override fun refresh() {
        val config = settings.current()
        synchronized(lock) {
            val running = probe
            // The same settings already being asked about: join, do not ask twice.
            if (running != null && running.config == config && running.job.isActive) return
            // Different settings: the old question is moot. Cancelling reaches the HTTP call.
            running?.job?.cancel()
            if (!config.isConfigured) {
                probe = null
                _availability.value = unconfigured()
                _checking.value = false
                return
            }
            _checking.value = true
            // LAZY, so the job is registered as the owner before it can finish and look for it.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val answer = client.health(config).toAvailability()
                synchronized(lock) {
                    // A probe that was superseded — new settings, or cancelled — answered late.
                    // Its answer is for settings nobody is looking at, and `checking` is the
                    // newer probe's to clear.
                    if (probe?.job !== coroutineContext[Job]) return@launch
                    probe = null
                    _availability.value = answer
                    _checking.value = false
                }
            }
            probe = Probe(config, job)
            job.start()
        }
    }

    override suspend fun enhance(
        image: Bitmap,
        style: AutoStyle,
    ): Result<AutoEnhanceProvider.Plan> = when (val plan = client.plan(image, style)) {
        is Result.Failure -> {
            // A generation that could not reach the server says the last probe is stale. One
            // re-probe, never a loop, and never a second upload of the photograph.
            if (plan.error.makesProbeStale()) refresh()
            plan
        }
        is Result.Success ->
            Result.Success(AutoEnhanceProvider.Plan(plan.value.adjustments, plan.value.reason))
    }

    private fun Result<Unit>.toAvailability(): Availability = when (this) {
        is Result.Success -> Availability.Ready
        is Result.Failure -> Availability.Unavailable(error)
    }

    private fun AppError.makesProbeStale(): Boolean =
        this is AppError.Io || this == AppError.Unavailable || this == AppError.Unauthorized

    /** Until the first probe answers, a configured server is "not known yet", not "unreachable". */
    private fun initialAvailability(config: MonetConfig): Availability =
        if (config.isConfigured) Availability.Unavailable(AppError.Unavailable) else unconfigured()

    private fun unconfigured() = Availability.Unavailable(AppError.Invalid(NO_SERVER))

    internal companion object {
        /** The tool tells this apart from an outage: one opens the 서버 설정 sheet, one does not. */
        const val NO_SERVER = AutoEnhanceProvider.NO_SERVER
    }
}
