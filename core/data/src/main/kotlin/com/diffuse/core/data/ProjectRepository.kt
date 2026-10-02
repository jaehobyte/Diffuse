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

/** specs/skin_retouch_pipeline.md §6: where [ProjectRepository.saveSkinRetouch] put both files. */
data class SkinRetouchFiles(val resultRef: ImageRef, val maskRef: ImageRef)

// One save per stored kind of pixels, and the retouch's pair adds a discard: splitting the
// interface would split one project folder across two contracts.
@Suppress("TooManyFunctions")
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

    /** Writes a generative result as `outpaint_<outpaintId>.png` in the project folder. */
    suspend fun saveOutpaintResult(
        projectId: String,
        outpaintId: String,
        bitmap: Bitmap,
    ): Result<ImageRef>

    /**
     * specs/skin_retouch_pipeline.md §6: writes the baked result as `retouch_<retouchId>.png` and
     * its binary support ([support] is `ALPHA_8`) as `mask_<maskId>.png`, each atomically. Both
     * exist on success; on failure or cancellation neither file this call wrote is left behind.
     */
    suspend fun saveSkinRetouch(
        projectId: String,
        retouchId: String,
        maskId: String,
        result: Bitmap,
        support: Bitmap,
    ): Result<SkinRetouchFiles>

    /**
     * Deletes the files [saveSkinRetouch] wrote for [retouchId] and [maskId], except any the
     * saved `document.json` references. The editor calls this only for its own request that was
     * never committed; what its in-memory history or redo still holds is its responsibility.
     */
    suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String): Result<Unit>

    /**
     * specs/multishot.md §7: writes one extracted subject as `shot_<fileId>.png`, atomically. A
     * failed or cancelled write leaves no file behind.
     */
    suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap): Result<ImageRef>

    /**
     * Deletes the subjects [saveShotSubject] wrote for [fileIds], except any the saved
     * `document.json` references. The editor calls this only for files its own session wrote and
     * never committed; what its in-memory history holds is its responsibility.
     */
    suspend fun discardShotSubjects(projectId: String, fileIds: List<String>): Result<Unit>

    suspend fun duplicate(id: String): Result<String>
    suspend fun delete(id: String): Result<Unit>
}
