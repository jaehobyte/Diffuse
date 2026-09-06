package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * specs/vibe_edit.md §8. The distance between `EditPlanProvider` and the wire: encode the
 * preview with the encoder the eraser already uses, send it with the sentence, return the steps.
 */
@Singleton
class GeminiPlanProvider @Inject internal constructor(
    private val client: GeminiPlanClient,
    settings: GeminiSettings,
    private val dispatchers: DispatcherProvider,
) : EditPlanProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /**
     * §8: derived from the key with **no probe**, for the reason generative_erase.md §7 gives —
     * the cheapest useful call to Gemini is a real one, billed to the user's key.
     */
    override val availability: StateFlow<Availability> = settings.config
        .map(::availabilityFor)
        .stateIn(scope, SharingStarted.Eagerly, availabilityFor(settings.config.value))

    override suspend fun plan(image: Bitmap, request: String): Result<EditPlan> =
        withContext(dispatchers.io) {
            if (request.isBlank()) {
                return@withContext Result.Failure(AppError.Invalid("empty request"))
            }
            val jpeg = encode(image) ?: return@withContext Result.Failure(AppError.TooLarge)
            coroutineContext.ensureActive()

            client.plan(jpeg, request)
        }

    /** §8 step 2: `GeminiImageCodec` unchanged and reused. No mask is involved. */
    private fun encode(image: Bitmap): ByteArray? {
        val scaled = GeminiImageCodec.downscale(image)
        return try {
            GeminiImageCodec.encode(scaled)
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }

    private companion object {
        fun availabilityFor(config: GeminiConfig): Availability =
            if (config.isConfigured) {
                Availability.Ready
            } else {
                Availability.Unavailable(AppError.Invalid("no api key"))
            }
    }
}
