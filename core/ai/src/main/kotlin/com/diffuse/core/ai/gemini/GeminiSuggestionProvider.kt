package com.diffuse.core.ai.gemini

import android.graphics.Bitmap
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.PromptSuggestionProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * specs/vibe_edit.md §14. [GeminiPlanProvider]'s shape: downscale and encode off the main thread
 * with `GeminiImageCodec`, recycle only the scaled copy, send nothing but the frame.
 */
@Singleton
class GeminiSuggestionProvider @Inject internal constructor(
    private val client: GeminiSuggestionClient,
    private val dispatchers: DispatcherProvider,
) : PromptSuggestionProvider {

    override suspend fun suggest(image: Bitmap): Result<List<PromptSuggestionId>> =
        withContext(dispatchers.io) {
            val jpeg = encode(image) ?: return@withContext Result.Failure(AppError.TooLarge)
            coroutineContext.ensureActive()
            client.suggest(jpeg)
        }

    private fun encode(image: Bitmap): ByteArray? {
        val scaled = GeminiImageCodec.downscale(image)
        return try {
            GeminiImageCodec.encode(scaled)
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }
}
