package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * specs/ai_provider.md §6, specs/vibe_edit.md §14. Returns [DEFAULT] unless [next] or [failNext]
 * overrides the following call. [hold] parks the next call until [release]; with
 * `ignoreCancellation` it parks in `NonCancellable`, so a test can prove a late answer is dropped.
 */
class FakePromptSuggestionProvider : PromptSuggestionProvider {

    var suggestCount: Int = 0
        private set

    var lastImage: Bitmap? = null
        private set

    private var nextIds: List<PromptSuggestionId>? = null
    private var nextError: AppError? = null
    private var gate: CompletableDeferred<Unit>? = null
    private var ignoreCancellation = false

    fun next(ids: List<PromptSuggestionId>) {
        nextIds = ids
    }

    fun failNext(error: AppError) {
        nextError = error
    }

    fun hold(ignoreCancellation: Boolean = false) {
        gate = CompletableDeferred()
        this.ignoreCancellation = ignoreCancellation
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun suggest(image: Bitmap): Result<List<PromptSuggestionId>> {
        suggestCount++
        lastImage = image
        val error = nextError.also { nextError = null }
        val ids = (nextIds ?: DEFAULT).also { nextIds = null }
        gate?.let { waiting ->
            if (ignoreCancellation) withContext(NonCancellable) { waiting.await() } else waiting.await()
        }
        return if (error != null) Result.Failure(error) else Result.Success(ids)
    }

    companion object {
        val DEFAULT = listOf(
            PromptSuggestionId.LiftShadows,
            PromptSuggestionId.Warm,
            PromptSuggestionId.VividColor,
        )
    }
}
