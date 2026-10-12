package com.diffuse.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
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
            is Result.Success -> when {
                !decoded.value.referencesResolve() -> {
                    logger?.warn(TAG, "document $id references a mask that is not in the list")
                    Result.Failure(AppError.Unsupported)
                }
                // specs/skin_retouch_pipeline.md §6: a retouch is only its stored pixels, so a
                // missing file is a reference that no longer resolves rather than a bad document.
                !decoded.value.skinRetouchFilesExist() -> {
                    logger?.warn(TAG, "document $id references a retouch file that is gone")
                    Result.Failure(AppError.MissingSource)
                }
                // specs/multishot.md §7: the same for a composite's subjects — never a partial one.
                !decoded.value.shotFilesExist() -> {
                    logger?.warn(TAG, "document $id references a multi-shot subject that is gone")
                    Result.Failure(AppError.MissingSource)
                }
                // §7: a hero mask of another size was cut for another canvas — a broken op.
                !decoded.value.heroMaskFits() -> {
                    logger?.warn(TAG, "document $id has a hero mask whose size does not match")
                    Result.Failure(AppError.Unsupported)
                }
                else -> decoded
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

    override suspend fun saveOutpaintResult(
        projectId: String,
        outpaintId: String,
        bitmap: Bitmap,
    ): Result<ImageRef> = withContext(dispatchers.io) {
        runCatchingIo {
            val file = files.outpaintFile(projectId, outpaintId)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            ImageRef(file.absolutePath)
        }
    }

    /**
     * The result goes first and the support second. Whatever this call wrote is deleted unless
     * both writes finished — an `IOException`, a cancellation between them or anything else — so a
     * failed request never leaves a half pair behind.
     */
    override suspend fun saveSkinRetouch(
        projectId: String,
        retouchId: String,
        maskId: String,
        result: Bitmap,
        support: Bitmap,
    ): Result<SkinRetouchFiles> = withContext(dispatchers.io) {
        val resultFile = files.retouchFile(projectId, retouchId)
        val maskFile = files.maskFile(projectId, maskId)
        val written = mutableListOf<File>()
        var complete = false
        try {
            runCatchingIo {
                files.writeAtomically(resultFile) { temporary ->
                    val compressed = temporary.outputStream().use {
                        result.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
                    }
                    if (!compressed) throw IOException("could not encode retouch $retouchId")
                }
                written += resultFile
                coroutineContext.ensureActive()
                files.writeAtomically(maskFile) { temporary -> MaskIo.write(temporary, support) }
                written += maskFile
                complete = true
                SkinRetouchFiles(
                    resultRef = ImageRef(resultFile.absolutePath),
                    maskRef = ImageRef(maskFile.absolutePath),
                )
            }
        } finally {
            if (!complete) written.forEach { it.delete() }
        }
    }

    /**
     * Only the on-disk document is consulted: a file it references is kept even when the caller
     * believes it is its own. A document that will not parse deletes nothing.
     */
    override suspend fun discardSkinRetouch(
        projectId: String,
        retouchId: String,
        maskId: String,
    ): Result<Unit> = withContext(dispatchers.io) {
        val documentFile = files.documentFile(projectId)
        val referenced = if (documentFile.isFile) {
            val document = runCatching { EditDocumentJson.decode(documentFile.readText(), logger) }
                .getOrElse { return@withContext Result.Failure(AppError.Unsupported) }
            document.referencedPaths()
        } else {
            emptySet()
        }
        listOf(files.retouchFile(projectId, retouchId), files.maskFile(projectId, maskId))
            .filterNot { it.absolutePath in referenced }
            .forEach { it.delete() }
        Result.Success(Unit)
    }

    override suspend fun saveShotSubject(
        projectId: String,
        fileId: String,
        subject: Bitmap,
    ): Result<ImageRef> = withContext(dispatchers.io) {
        val file = files.shotFile(projectId, fileId)
        var complete = false
        try {
            runCatchingIo {
                files.writeAtomically(file) { temporary ->
                    val compressed = temporary.outputStream().use {
                        subject.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
                    }
                    if (!compressed) throw IOException("could not encode shot $fileId")
                }
                coroutineContext.ensureActive()
                complete = true
                ImageRef(file.absolutePath)
            }
        } finally {
            if (!complete) file.delete()
        }
    }

    /** Only the on-disk document is consulted, as in [discardSkinRetouch]. */
    override suspend fun discardShotSubjects(
        projectId: String,
        fileIds: List<String>,
    ): Result<Unit> = withContext(dispatchers.io) {
        val documentFile = files.documentFile(projectId)
        val referenced = if (documentFile.isFile) {
            val document = runCatching { EditDocumentJson.decode(documentFile.readText(), logger) }
                .getOrElse { return@withContext Result.Failure(AppError.Unsupported) }
            document.referencedPaths()
        } else {
            emptySet()
        }
        fileIds.map { files.shotFile(projectId, it) }
            .filterNot { it.absolutePath in referenced }
            .forEach { it.delete() }
        Result.Success(Unit)
    }

    override suspend fun duplicate(id: String): Result<String> = withContext(dispatchers.io) {
        val original = dao.findById(id) ?: return@withContext Result.Failure(AppError.MissingSource)
        val copyId = newId()
        runCatchingIo {
            files.projectDir(id).copyRecursively(files.projectDir(copyId), overwrite = true)
            val document = EditDocumentJson.decode(files.documentFile(copyId).readText(), logger)
                .withPathsMoved(from = files.projectDir(id), to = files.projectDir(copyId))
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

private fun EditDocument.skinRetouchFilesExist(): Boolean = skinRetouches().all { retouch ->
    File(retouch.resultRef.path).isFile && mask(retouch.maskId)?.maskRef?.let { File(it.path).isFile } == true
}

private fun EditDocument.shotFilesExist(): Boolean =
    multiShot()?.shotRefs().orEmpty().all { File(it.path).isFile }

/** specs/multishot.md §7: the stored hero mask is the size the op says it is. */
private fun EditDocument.heroMaskFits(): Boolean {
    val hero = multiShot()?.timeline?.hero ?: return true
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(hero.ref.path, bounds)
    return bounds.outWidth == hero.widthPx && bounds.outHeight == hero.heightPx
}

/** Every file a composite owns: its subjects and, once selected, the last moment's mask. */
private fun Operation.MultiShot.shotRefs(): List<ImageRef> =
    shots.map { it.subjectRef } + listOfNotNull(timeline?.hero?.ref)

private fun EditDocument.referencedPaths(): Set<String> = buildSet {
    add(File(source.path).absolutePath)
    operations.forEach { operation ->
        when (operation) {
            is Operation.Mask -> listOf(operation.maskRef)
            is Operation.GenerativeErase -> listOf(operation.resultRef)
            is Operation.GenerativeFill -> listOf(operation.resultRef)
            is Operation.Outpaint -> listOf(operation.resultRef)
            is Operation.SkinRetouch -> listOf(operation.resultRef)
            is Operation.MultiShot -> operation.shotRefs()
            is Operation.Adjust, is Operation.CutOut, is Operation.Crop -> emptyList()
        }.forEach { add(File(it.path).absolutePath) }
    }
}

/**
 * specs/skin_retouch_pipeline.md §6 "복제 경로 재작성", widened by specs/multishot.md §7: a copy points
 * at the copy's own files — its source, every stored mask and result, and every multi-shot subject —
 * so deleting the original cannot break it. Only files that were in the original folder move; a
 * reference anywhere else is kept unchanged.
 */
private fun EditDocument.withPathsMoved(from: File, to: File): EditDocument {
    fun moved(ref: ImageRef): ImageRef {
        val file = File(ref.path)
        return if (file.parentFile?.absolutePath == from.absolutePath) {
            ImageRef(File(to, file.name).absolutePath)
        } else {
            ref
        }
    }
    return copy(
        source = moved(source),
        operations = operations.map { operation ->
            when (operation) {
                is Operation.Mask -> operation.copy(maskRef = moved(operation.maskRef))
                is Operation.GenerativeErase -> operation.copy(resultRef = moved(operation.resultRef))
                is Operation.GenerativeFill -> operation.copy(resultRef = moved(operation.resultRef))
                is Operation.Outpaint -> operation.copy(resultRef = moved(operation.resultRef))
                is Operation.SkinRetouch -> operation.copy(resultRef = moved(operation.resultRef))
                is Operation.MultiShot -> operation.copy(
                    shots = operation.shots.map { it.copy(subjectRef = moved(it.subjectRef)) },
                    timeline = operation.timeline?.let { timeline ->
                        timeline.copy(hero = timeline.hero?.let { it.copy(ref = moved(it.ref)) })
                    },
                )
                is Operation.Adjust, is Operation.CutOut, is Operation.Crop -> operation
            }
        },
    )
}

private fun ProjectEntity.toSummary() = ProjectSummary(
    id = id,
    createdAt = createdAt,
    updatedAt = updatedAt,
    widthPx = width,
    heightPx = height,
    thumbPath = thumbPath,
)

