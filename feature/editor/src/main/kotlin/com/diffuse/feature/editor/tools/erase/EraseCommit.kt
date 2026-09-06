package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation

/**
 * specs/generative_erase.md §10. An erase stores the mask it actually used — the dilated one
 * ([EraseMask]) — and the result that references it, as **one** history entry.
 *
 * Shared by the 지우기 tool and the 지시 tool's `Erase` step, because two copies of "which mask did
 * we erase through" is exactly how the two paths drift apart.
 */
class EraseCommit(
    private val saveMask: suspend (maskId: String, mask: Bitmap) -> Result<ImageRef>,
    private val saveResult: suspend (eraseId: String, result: Bitmap) -> Result<ImageRef>,
) {

    /**
     * Writes both files first: a document pointing at a file that is not there is worse than an
     * erase the user has to repeat.
     */
    suspend fun apply(
        document: EditDocument,
        dilatedMask: Bitmap,
        result: Bitmap,
    ): Result<EditDocument> {
        val maskId = newId()
        return when (val saved = saveMask(maskId, dilatedMask)) {
            is Result.Failure -> saved
            is Result.Success -> store(document, maskId, saved.value, result)
        }
    }

    private suspend fun store(
        document: EditDocument,
        maskId: String,
        maskRef: ImageRef,
        result: Bitmap,
    ): Result<EditDocument> {
        val eraseId = newId()
        return when (val saved = saveResult(eraseId, result)) {
            is Result.Failure -> saved
            is Result.Success -> Result.Success(
                document.withMask(maskRef, maskId)
                    // The margin mask is a reference for the erase, not a new selection: what the
                    // user chose stays active, so a following adjust or cut-out is still theirs.
                    .copy(activeMaskId = document.activeMaskId)
                    .withGenerativeErase(maskId, saved.value, eraseId)
                    .let(::underTheAdjustments),
            )
        }
    }

    /**
     * The result pixels are generated from the frame *without* the adjustments
     * ([eraseInput]), so they belong under every `Adjust`, not after them.
     *
     * Appending would leave an adjustment made before the erase pinned in front of it, where
     * the erase result then overwrites the region it just produced: re-dragging that slider
     * stops reaching the erased hole while the rest of the photo still moves.
     * `EditDocument.withAdjust` keeps a slider's list position for life (edit_model.md), so the
     * erase is what has to move.
     */
    private fun underTheAdjustments(document: EditDocument): EditDocument {
        val operations = document.operations
        val firstAdjust = operations.indexOfFirst { it is Operation.Adjust }
        if (firstAdjust < 0) return document
        val erase = operations.last()
        return document.copy(
            operations = operations.dropLast(1).toMutableList().apply { add(firstAdjust, erase) },
        )
    }
}
