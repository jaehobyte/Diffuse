package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.StateFlow

/** specs/auto_enhance.md §4. The three styles MonetGPT's released config carries. */
enum class AutoStyle { Balanced, Vibrant, Retro }

/**
 * specs/auto_enhance.md §5. What the auto boost is: **adjustments, not pixels** (ADR-015).
 *
 * The server runs the model and the device renders the plan, so the result lands in the document
 * as ordinary `Adjust` ops the user can then drag. A provider that returned a `Bitmap` would be
 * one opaque op nobody could disagree with — the same argument style_match.md §2 makes about
 * LUTs, reached again.
 *
 * `AdjustKind` is the one type `core:ai` reaches into `core:imaging` for (ai_provider.md §2), so
 * this adds no module edge.
 */
interface AutoEnhanceProvider {

    val availability: StateFlow<Availability>

    /** [reason] is the model's own English sentence; specs/auto_enhance.md §6 shows it as-is. */
    data class Plan(val adjustments: Map<AdjustKind, Float>, val reason: String)

    /** The adjustments to apply, already in −1..1. Never pixels. */
    suspend fun enhance(image: Bitmap, style: AutoStyle): Result<Plan>
}
