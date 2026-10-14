package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
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
 * specs/vibe_edit.md §8, §14. The distance between `EditPlanProvider` and the wire: encode the
 * preview with the encoder the eraser already uses, send it with the request, return the proposal.
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
        .map(::geminiAvailability)
        .stateIn(scope, SharingStarted.Eagerly, geminiAvailability(settings.config.value))

    /** §14: only a sent 지시 needs words; 보정 추천 may ask about the photo alone. */
    override suspend fun plan(image: Bitmap, request: PlanRequest): Result<PlanProposal> =
        withContext(dispatchers.io) {
            if (request.mode == PlanMode.Direct && request.text.isBlank()) {
                return@withContext Result.Failure(AppError.Invalid("empty request"))
            }
            val jpeg = GeminiImageCodec.encodeScaled(image) ?: return@withContext Result.Failure(AppError.TooLarge)
            coroutineContext.ensureActive()

            client.plan(jpeg, request)
        }
}
