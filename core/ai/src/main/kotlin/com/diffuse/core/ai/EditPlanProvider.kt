package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/vibe_edit.md §7. One step of a plan; each maps onto a tool that already exists
 * (specs/vibe_edit.md §4), so a plan adds no `Operation` and no renderer path.
 */
sealed interface PlanStep {

    data class Select(val phrase: String) : PlanStep

    /**
     * [kind] is the enum rather than the wire's function name: returning a `String` would split
     * one validation across two modules (specs/ai_provider.md §2).
     */
    data class Adjust(val kind: AdjustKind, val value: Float, val masked: Boolean) : PlanStep

    data object Erase : PlanStep

    data object CutOut : PlanStep
}

/** [steps] in execution order. Empty means the model declined to act — not a failure. */
data class EditPlan(val steps: List<PlanStep>)

/**
 * specs/vibe_edit.md §7. Decides which of the editor's tools to run for a request. It chooses a
 * workflow and never returns pixels; executing the steps is `feature:editor`'s job (§9).
 */
interface EditPlanProvider {

    val availability: StateFlow<Availability>

    /** One call, no session, no state. */
    suspend fun plan(image: Bitmap, request: String): Result<EditPlan>
}
