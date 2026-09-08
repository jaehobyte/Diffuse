package com.diffuse.core.ai

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import kotlinx.coroutines.flow.StateFlow

/**
 * specs/style_match.md §5 step 2 — and **only** step 2. The local matcher runs first and answers
 * most references without a round trip; this is what happens when it cannot.
 *
 * It asks for **numbers, not pixels**, for §2's reason reached again: a graded photograph is one
 * op nobody can disagree with, while adjustments land in the same sliders the user already has.
 *
 * `AdjustKind` is the one type `core:ai` reaches into `core:imaging` for (ai_provider.md §2), so
 * this adds no module edge.
 */
interface MatchStyleProvider {

    val availability: StateFlow<Availability>

    /**
     * @param image the photo being edited, and [reference] the one whose look to copy. The
     * reference is sent once and never stored (§10).
     * @return the adjustments that move [image] towards [reference], already in −1..1.
     */
    suspend fun match(image: Bitmap, reference: Bitmap): Result<Map<AdjustKind, Float>>
}
