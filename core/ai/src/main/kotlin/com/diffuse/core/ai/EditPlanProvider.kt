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

    /**
     * specs/vibe_edit.md §4.1. The model picks a **ratio**, never a rectangle: the rect is
     * computed from the preset the 자르기 chips already use, and the 자르기 tool opens straight
     * afterwards so the user chooses the framing.
     */
    data class Crop(val ratio: CropRatio) : PlanStep
}

/**
 * §4.1's closed set — the four preset chips specs/crop.md ships, minus 자유. A model choosing
 * "free" would be choosing nothing, so it is not on the wire.
 *
 * It lives here rather than in `feature:editor` for the reason `PlanStep.Adjust` carries
 * `AdjustKind` (specs/ai_provider.md §2): a plan model that cannot say what it means pushes one
 * validation into two modules. It maps to the tool's `AspectPreset` at the feature boundary;
 * `core:ai` never reaches for crop geometry.
 */
enum class CropRatio { Square, Portrait4x5, Story9x16, Landscape16x9 }

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
