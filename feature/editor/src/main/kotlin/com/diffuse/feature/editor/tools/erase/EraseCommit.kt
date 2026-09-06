package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef

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
                    .withGenerativeErase(maskId, saved.value, eraseId),
            )
        }
    }
}
