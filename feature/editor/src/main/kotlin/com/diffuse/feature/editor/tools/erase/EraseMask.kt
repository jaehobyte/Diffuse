package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import com.diffuse.feature.editor.tools.select.MaskOps
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * specs/generative_erase.md §4. The mask the model is shown is the selection **plus a margin**.
 *
 * SAM 3 cuts at the visible edge of the thing, so the object's antialiased fringe and its contact
 * shadow sit just outside the mask. Whitening only what is inside leaves the model that fringe as
 * context, and it paints around it: on the device the erased object left a visible outline.
 *
 * The margin has to be on the mask the **document stores**, not only on the pixels sent to Gemini.
 * The renderer composes `lerp(in, result, maskAlpha)` (§10), so anything the model filled outside
 * the stored mask is restored from the source — the halo would survive an otherwise perfect erase.
 */
object EraseMask {

    /** Of the short edge. A dozen pixels at preview resolution, which is what a fringe measures. */
    const val MARGIN_FRACTION = 0.015f

    /** A thumbnail-sized mask still needs a real margin, not a rounding artefact. */
    const val MARGIN_MIN_PX = 4

    fun marginPx(mask: Bitmap): Int =
        maxOf(MARGIN_MIN_PX, (MARGIN_FRACTION * min(mask.width, mask.height)).roundToInt())

    fun dilated(mask: Bitmap): Bitmap = MaskOps.dilated(mask, marginPx(mask))
}
