package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer

/**
 * The frame the eraser is shown: the preview **minus the adjustments**.
 *
 * specs/generative_erase.md §10 makes the result an op that carries its own pixels, so whatever
 * is baked into them can never be re-adjusted. Rendering without the `Adjust` ops keeps the
 * result under the adjust stack, where [EraseCommit] then places the op — together that is what
 * lets a slider dragged afterwards still reach the erased hole.
 *
 * Crop and earlier erases stay, so the model sees the frame the mask was drawn on. Dropping the
 * adjustments is also what makes this render cheap: their maths is the expensive part.
 */
suspend fun eraseInput(
    renderer: Renderer,
    document: EditDocument,
    targetLongEdgePx: Int,
): Bitmap? {
    val plain = document.copy(
        operations = document.operations.filterNot { it is Operation.Adjust },
    )
    return (renderer.preview(plain, targetLongEdgePx) as? Result.Success)?.value
}
