package com.diffuse.feature.editor.tools

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer

/**
 * One rule, in two halves, for every operation that carries its own pixels
 * (specs/generative_erase.md §10, generative_fill.md §5).
 *
 * A generative result is baked pixels, so whatever is already in them can never be re-adjusted.
 * The model is therefore shown the frame **without** the `Adjust` ops ([generativeInput]) and the
 * op it produces is placed **under** them ([underTheAdjustments]). Either half alone is wrong: a
 * plain frame stored on top of the stack still gets overwritten, and a repositioned op carrying
 * baked adjustments doubles them.
 *
 * T70: written once here rather than twice, because 지우기 and 채우기 drifting apart on this is
 * exactly the defect the second device run found — 채우기 had neither half.
 */

/**
 * The frame a generative tool is shown: the preview minus the adjustments.
 *
 * Crop, outpaint and earlier generative results stay, so the model sees the frame the mask was
 * drawn on. Dropping the adjustments is also what makes this render cheap: their maths is the
 * expensive part.
 */
suspend fun generativeInput(
    renderer: Renderer,
    document: EditDocument,
    targetLongEdgePx: Int,
): Bitmap? {
    val plain = document.copy(
        operations = document.operations.filterNot { it is Operation.Adjust },
    )
    return (renderer.preview(plain, targetLongEdgePx) as? Result.Success)?.value
}

/**
 * Moves the **last** operation to just before the first `Adjust`, leaving it where it is when
 * nothing has been adjusted yet.
 *
 * Appending would leave an adjustment made before the result pinned in front of it, where the
 * result then overwrites the region it just produced: re-dragging that slider stops reaching the
 * generated region while the rest of the photo still moves. `EditDocument.withAdjust` keeps a
 * slider's list position for life (edit_model.md), so the generative op is what has to move.
 *
 * Moving it puts the op ahead of the `Mask` ops it names, which is only legal because masks change
 * no pixels and are looked up by id.
 */
fun EditDocument.underTheAdjustments(): EditDocument {
    val firstAdjust = operations.indexOfFirst { it is Operation.Adjust }
    if (firstAdjust < 0) return this
    val generated = operations.last()
    return copy(
        operations = operations.dropLast(1).toMutableList().apply { add(firstAdjust, generated) },
    )
}
