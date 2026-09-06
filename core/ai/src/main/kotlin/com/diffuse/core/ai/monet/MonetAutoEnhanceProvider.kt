package com.diffuse.core.ai.monet

import android.graphics.Bitmap
import com.diffuse.core.ai.AutoEnhanceProvider
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.Availability
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
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
    settings: MonetSettings,
    dispatchers: DispatcherProvider,
) : AutoEnhanceProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val _availability = MutableStateFlow<Availability>(unconfigured())
    override val availability: StateFlow<Availability> = _availability.asStateFlow()

    init {
        // §4: a probe rather than Gemini's "is a key present" rule — this server is the user's
        // own, so reachability is the real question, and re-probing on every address change is
        // what makes the 서버 설정 sheet's Save mean something.
        scope.launch {
            settings.config.collect { config ->
                _availability.value = when {
                    !config.isConfigured -> unconfigured()
                    client.health() -> Availability.Ready
                    else -> Availability.Unavailable(AppError.Unavailable)
                }
            }
        }
    }

    override suspend fun enhance(
        image: Bitmap,
        style: AutoStyle,
    ): Result<AutoEnhanceProvider.Plan> = when (val plan = client.plan(image, style)) {
        is Result.Failure -> plan
        is Result.Success ->
            Result.Success(AutoEnhanceProvider.Plan(plan.value.adjustments, plan.value.reason))
    }

    private fun unconfigured() = Availability.Unavailable(AppError.Invalid(NO_SERVER))

    internal companion object {
        /** The tool tells this apart from an outage: one opens the 서버 설정 sheet, one does not. */
        const val NO_SERVER = "no server address"
    }
}
