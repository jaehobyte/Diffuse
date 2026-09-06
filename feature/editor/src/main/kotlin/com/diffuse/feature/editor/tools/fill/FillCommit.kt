package com.diffuse.feature.editor.tools.fill

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef

/**
 * specs/generative_fill.md §5, and generative_erase.md §10's shape. A fill stores the mask it
 * actually used — the padded rectangle ([FillMask]) — and the result that references it, as
 * **one** history entry.
 *
 * The rectangle has to be what the *document* stores, not only what went on the wire: the renderer
 * composes `lerp(in, result, maskAlpha)`, so a result composited through the silhouette would
 * throw away every pixel the model painted outside it — which is most of the new object. That is
 * T50's argument for the erase margin, made again for a bigger region.
 *
 * Shared by the 채우기 tool and the 지시 tool's `Fill` step, because two copies of "which mask did
 * we fill through" is exactly how the two paths drift apart.
 */
class FillCommit(
    private val saveMask: suspend (maskId: String, mask: Bitmap) -> Result<ImageRef>,
    private val saveResult: suspend (fillId: String, result: Bitmap) -> Result<ImageRef>,
) {

    /**
     * Writes both files first: a document pointing at a file that is not there is worse than a
     * fill the user has to repeat.
     */
    suspend fun apply(
        document: EditDocument,
        rectangle: Bitmap,
        prompt: String,
        result: Bitmap,
    ): Result<EditDocument> {
        val maskId = newId()
        return when (val saved = saveMask(maskId, rectangle)) {
            is Result.Failure -> saved
            is Result.Success -> store(document, maskId, saved.value, prompt, result)
        }
    }

    private suspend fun store(
        document: EditDocument,
        maskId: String,
        maskRef: ImageRef,
        prompt: String,
        result: Bitmap,
    ): Result<EditDocument> {
        val fillId = newId()
        return when (val saved = saveResult(fillId, result)) {
            is Result.Failure -> saved
            is Result.Success -> Result.Success(
                document.withMask(maskRef, maskId)
                    // The rectangle is a reference for the fill, not a new selection: what the
                    // user chose stays active, so a following adjust or cut-out is still theirs.
                    .copy(activeMaskId = document.activeMaskId)
                    .withGenerativeFill(maskId, saved.value, prompt, fillId),
            )
        }
    }
}
