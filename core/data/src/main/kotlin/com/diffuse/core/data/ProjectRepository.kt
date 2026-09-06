package com.diffuse.core.data

import android.graphics.Bitmap
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.flow.Flow

/** specs/persistence.md. A project is a folder plus one Room row. */
data class ProjectSummary(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val widthPx: Int,
    val heightPx: Int,
    val thumbPath: String,
)

interface ProjectRepository {
    /** Newest `updatedAt` first. */
    fun observeAll(): Flow<List<ProjectSummary>>
    suspend fun create(source: SourceImage): Result<String>
    suspend fun load(id: String): Result<EditDocument>
    suspend fun save(document: EditDocument): Result<Unit>

    /**
     * Writes a selection as `mask_<maskId>.png` in the project folder and hands back the
     * reference to store in `Operation.Mask`. [alpha] must be `ALPHA_8`.
     */
    suspend fun saveMask(
        projectId: String,
        maskId: String,
        alpha: Bitmap,
    ): Result<ImageRef>
    /** Writes a generative result as `erase_<eraseId>.png` in the project folder. */
    suspend fun saveEraseResult(
        projectId: String,
        eraseId: String,
        bitmap: Bitmap,
    ): Result<ImageRef>

    /** Writes a generative result as `fill_<fillId>.png` in the project folder. */
    suspend fun saveFillResult(
        projectId: String,
        fillId: String,
        bitmap: Bitmap,
    ): Result<ImageRef>

    suspend fun duplicate(id: String): Result<String>
    suspend fun delete(id: String): Result<Unit>
}
