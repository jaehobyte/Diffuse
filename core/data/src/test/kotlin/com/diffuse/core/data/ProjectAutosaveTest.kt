package com.diffuse.core.data

import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** specs/persistence.md §Autosave, on virtual time so the 2s is asserted, not waited out. */
class ProjectAutosaveTest {

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private class RecordingRepository : ProjectRepository {
        val saves = mutableListOf<EditDocument>()
        val deletes = mutableListOf<String>()

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success("id")
        override suspend fun load(id: String): Result<EditDocument> =
            Result.Failure(com.diffuse.core.common.AppError.MissingSource)

        override suspend fun save(document: EditDocument): Result<Unit> {
            saves += document
            return Result.Success(Unit)
        }

        override suspend fun saveMask(
            projectId: String,
            maskId: String,
            alpha: android.graphics.Bitmap,
        ): Result<com.diffuse.core.imaging.model.ImageRef> =
            Result.Success(com.diffuse.core.imaging.model.ImageRef("/mask_$maskId.png"))

        override suspend fun saveEraseResult(
            projectId: String,
            eraseId: String,
            bitmap: android.graphics.Bitmap,
        ): Result<com.diffuse.core.imaging.model.ImageRef> =
            Result.Success(com.diffuse.core.imaging.model.ImageRef("/erase_$eraseId.png"))

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> {
            deletes += id
            return Result.Success(Unit)
        }
    }

    @Test
    fun `two edits inside the debounce window collapse into one save`() = runTest {
        val repository = RecordingRepository()
        val autosave = ProjectAutosave(repository)
        val documents = MutableSharedFlow<EditDocument>(replay = 1)
        val job = launch { autosave.run(documents) }

        documents.emit(document.withAdjust(AdjustKind.Exposure, 0.1f))
        advanceTimeBy(500)
        documents.emit(document.withAdjust(AdjustKind.Exposure, 0.2f))
        advanceTimeBy(AUTOSAVE_DEBOUNCE_MS + 100)
        advanceUntilIdle()

        assertEquals(1, repository.saves.size)
        assertEquals(0.2f, repository.saves.single().adjustValue(AdjustKind.Exposure), 0f)
        job.cancel()
    }

    @Test
    fun `edits separated by more than the window save twice`() = runTest {
        val repository = RecordingRepository()
        val autosave = ProjectAutosave(repository)
        val documents = MutableSharedFlow<EditDocument>(replay = 1)
        val job = launch { autosave.run(documents) }

        documents.emit(document.withAdjust(AdjustKind.Exposure, 0.1f))
        advanceTimeBy(AUTOSAVE_DEBOUNCE_MS + 100)
        documents.emit(document.withAdjust(AdjustKind.Contrast, 0.3f))
        advanceTimeBy(AUTOSAVE_DEBOUNCE_MS + 100)
        advanceUntilIdle()

        assertEquals(2, repository.saves.size)
        job.cancel()
    }

    @Test
    fun `saveNow does not wait for the debounce`() = runTest {
        val repository = RecordingRepository()

        ProjectAutosave(repository).saveNow(document)

        assertEquals(1, repository.saves.size)
    }

    @Test
    fun `an untouched project is discarded on exit`() = runTest {
        val repository = RecordingRepository()

        val discarded = ProjectAutosave(repository).discardIfUntouched(document)

        assertTrue(discarded)
        assertEquals(listOf(document.id), repository.deletes)
    }

    @Test
    fun `a project with operations is kept`() = runTest {
        val repository = RecordingRepository()

        val discarded = ProjectAutosave(repository)
            .discardIfUntouched(document.withAdjust(AdjustKind.Exposure, 0.5f))

        assertFalse(discarded)
        assertTrue(repository.deletes.isEmpty())
    }

    @Test
    fun `a project that was already saved is kept even when empty`() = runTest {
        val repository = RecordingRepository()
        val autosave = ProjectAutosave(repository)
        autosave.saveNow(document)

        assertFalse(autosave.discardIfUntouched(document))
        assertTrue(repository.deletes.isEmpty())
    }
}
