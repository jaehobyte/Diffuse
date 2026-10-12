package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * specs/ai_provider.md §6. Returns [DEFAULT_PLAN] — the request specs/vibe_edit.md was written
 * from — unless [next] or [failNext] overrides the following call. [hold] parks the next call in
 * `NonCancellable` until [release], so a test can prove a late plan is dropped (§14).
 */
class FakePlanProvider : EditPlanProvider {

    private val _availability = MutableStateFlow<Availability>(Availability.Ready)
    override val availability: StateFlow<Availability> = _availability

    var planCount: Int = 0
        private set

    private var nextPlan: EditPlan? = null
    private var nextError: AppError? = null
    private var gate: CompletableDeferred<Unit>? = null

    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    fun next(plan: EditPlan) {
        nextPlan = plan
    }

    fun failNext(error: AppError) {
        nextError = error
    }

    fun setAvailability(value: Availability) {
        _availability.value = value
    }

    override suspend fun plan(image: Bitmap, request: String): Result<EditPlan> {
        nextError?.let { nextError = null; return Result.Failure(it) }
        planCount++
        val plan = nextPlan ?: DEFAULT_PLAN
        nextPlan = null
        gate?.let { waiting -> withContext(NonCancellable) { waiting.await() } }
        return Result.Success(plan)
    }

    companion object {
        val DEFAULT_PLAN = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
        )
    }
}
