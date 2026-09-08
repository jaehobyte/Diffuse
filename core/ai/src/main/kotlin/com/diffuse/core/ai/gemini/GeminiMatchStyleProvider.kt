package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.MatchStyleProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
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
 * specs/style_match.md §5 step 2. The distance between `MatchStyleProvider` and the wire: encode
 * both photographs with the codec every other Gemini call uses, send them once, return numbers.
 */
@Singleton
class GeminiMatchStyleProvider @Inject internal constructor(
    private val client: GeminiMatchStyleClient,
    settings: GeminiSettings,
    private val dispatchers: DispatcherProvider,
) : MatchStyleProvider {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** §5: the key, with **no probe** — generative_erase.md §7's argument, unchanged. */
    override val availability: StateFlow<Availability> = settings.config
        .map(::availabilityFor)
        .stateIn(scope, SharingStarted.Eagerly, availabilityFor(settings.config.value))

    override suspend fun match(
        image: Bitmap,
        reference: Bitmap,
    ): Result<Map<AdjustKind, Float>> = withContext(dispatchers.io) {
        val photo = encode(image) ?: return@withContext Result.Failure(AppError.TooLarge)
        coroutineContext.ensureActive()
        val look = encode(reference) ?: return@withContext Result.Failure(AppError.TooLarge)
        coroutineContext.ensureActive()

        client.match(photo, look)
    }

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
