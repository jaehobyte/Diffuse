package com.diffuse.core.data

import android.graphics.Bitmap
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.common.Result
import com.diffuse.core.common.newId
import com.diffuse.core.data.db.ProjectDao
import com.diffuse.core.data.db.ProjectEntity
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.EditDocumentJson
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/** specs/persistence.md: 512px long edge, rendered with the current ops. */
const val THUMBNAIL_LONG_EDGE_PX = 512

private const val TAG = "ProjectRepository"
private const val JPEG_QUALITY = 92
private const val PNG_QUALITY = 100

class DefaultProjectRepository(
    private val dao: ProjectDao,
    private val files: ProjectFiles,
    private val renderer: Renderer,
    private val dispatchers: DispatcherProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger? = null,
) : ProjectRepository {

    override fun observeAll(): Flow<List<ProjectSummary>> =
        dao.observeAll().map { rows -> rows.map(ProjectEntity::toSummary) }

    override suspend fun create(source: SourceImage): Result<String> =
        withContext(dispatchers.io) {
            val id = newId()
            val now = clock()
            runCatchingIo {
                val extension = if (source.hasAlpha) "png" else "jpg"
                val sourceFile = files.sourceFile(id, extension)
                sourceFile.parentFile?.mkdirs()
                sourceFile.outputStream().use { stream ->
                    val format =
                        if (source.hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    source.bitmap.compress(format, JPEG_QUALITY, stream)
                }
                val document = EditDocument(
                    id = id,
                    source = ImageRef(sourceFile.absolutePath),
                    createdAt = now,
                    updatedAt = now,
                )
                writeDocument(document)
                dao.upsert(
                    ProjectEntity(
                        id = id,
                        createdAt = now,
                        updatedAt = now,
                        width = source.widthPx,
                        height = source.heightPx,
                        thumbPath = files.thumbFile(id).absolutePath,
                    ),
                )
                id
            }
        }

    override suspend fun load(id: String): Result<EditDocument> = withContext(dispatchers.io) {
        val file = files.documentFile(id)
        if (!file.isFile) return@withContext Result.Failure(AppError.MissingSource)
        when (val decoded = runCatchingIo { EditDocumentJson.decode(file.readText(), logger) }) {
            is Result.Failure -> decoded
            // specs/edit_model.md: a dangling mask reference is not something to load past —
            // dropping it silently would drop the user's selection without telling them.
            is Result.Success ->
                if (decoded.value.referencesResolve()) {
                    decoded
                } else {
                    logger?.warn(TAG, "document $id references a mask that is not in the list")
                    Result.Failure(AppError.Unsupported)
                }
        }
    }

    override suspend fun saveMask(
        projectId: String,
        maskId: String,
        alpha: Bitmap,
    ): Result<ImageRef> = withContext(dispatchers.io) {
        runCatchingIo {
            val file = files.maskFile(projectId, maskId)
            MaskIo.write(file, alpha)
            ImageRef(file.absolutePath)
        }
    }

    /**
     * The JSON is written first and the thumbnail after: specs/persistence.md requires the
     * thumbnail render not to hold up the save, and a stale thumbnail is recoverable while
     * a lost document is not.
     */
    override suspend fun save(document: EditDocument): Result<Unit> =
        withContext(dispatchers.io) {
            val now = clock()
            val stamped = document.copy(updatedAt = now)
            when (val written = runCatchingIo { writeDocument(stamped) }) {
                is Result.Failure -> written
                is Result.Success -> {
                    val thumbnail = renderThumbnail(stamped)
                    val existing = dao.findById(stamped.id)
                    dao.upsert(
                        ProjectEntity(
                            id = stamped.id,
                            createdAt = existing?.createdAt ?: stamped.createdAt,
                            updatedAt = now,
                            width = thumbnail?.width ?: existing?.width ?: 0,
                            height = thumbnail?.height ?: existing?.height ?: 0,
                            thumbPath = files.thumbFile(stamped.id).absolutePath,
                        ),
                    )
                    Result.Success(Unit)
                }
            }
        }

    override suspend fun saveEraseResult(
        projectId: String,
        eraseId: String,
        bitmap: Bitmap,
    ): Result<ImageRef> = withContext(dispatchers.io) {
        runCatchingIo {
            val file = files.eraseFile(projectId, eraseId)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            ImageRef(file.absolutePath)
        }
    }

    override suspend fun saveFillResult(
        projectId: String,
        fillId: String,
        bitmap: Bitmap,
    ): Result<ImageRef> = withContext(dispatchers.io) {
        runCatchingIo {
            val file = files.fillFile(projectId, fillId)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            ImageRef(file.absolutePath)
        }
    }

    override suspend fun duplicate(id: String): Result<String> = withContext(dispatchers.io) {
        val original = dao.findById(id) ?: return@withContext Result.Failure(AppError.MissingSource)
        val copyId = newId()
        runCatchingIo {
            files.projectDir(id).copyRecursively(files.projectDir(copyId), overwrite = true)
            val document = EditDocumentJson.decode(files.documentFile(copyId).readText(), logger)
            val now = clock()
            writeDocument(document.copy(id = copyId, createdAt = now, updatedAt = now))
            dao.upsert(original.copy(id = copyId, createdAt = now, updatedAt = now,
                thumbPath = files.thumbFile(copyId).absolutePath))
            copyId
        }
    }

    /** specs/persistence.md: a folder that will not delete still loses its row, with a log. */
    override suspend fun delete(id: String): Result<Unit> = withContext(dispatchers.io) {
        val removed = runCatching { files.projectDir(id).deleteRecursively() }.getOrDefault(false)
        if (!removed) logger?.warn(TAG, "could not remove the folder for $id; dropping the row anyway")
        dao.deleteById(id)
        Result.Success(Unit)
    }

    private fun writeDocument(document: EditDocument) {
        files.writeAtomically(files.documentFile(document.id)) { temporary ->
            temporary.writeText(EditDocumentJson.encode(document))
        }
    }

    private suspend fun renderThumbnail(document: EditDocument): Bitmap? =
        when (val rendered = renderer.preview(document, THUMBNAIL_LONG_EDGE_PX)) {
            is Result.Failure -> {
                logger?.warn(TAG, "thumbnail render failed for ${document.id}: ${rendered.error}")
                null
            }
            is Result.Success -> rendered.value.also { bitmap ->
                runCatching {
                    files.writeAtomically(files.thumbFile(document.id)) { temporary ->
                        temporary.outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
                        }
                    }
                }
            }
        }

    private inline fun <T> runCatchingIo(block: () -> T): Result<T> = try {
        Result.Success(block())
    } catch (e: IOException) {
        Result.Failure(AppError.Io(e))
    }
}

private fun ProjectEntity.toSummary() = ProjectSummary(
    id = id,
    createdAt = createdAt,
    updatedAt = updatedAt,
    widthPx = width,
    heightPx = height,
    thumbPath = thumbPath,
)

