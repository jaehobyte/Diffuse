package com.diffuse.feature.browse

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** specs/browse.md §Import: the URI becomes a project, or a snackbar. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseImportTest {

    private class RecordingRepository(
        private val result: Result<String> = Result.Success("project-1"),
    ) : ProjectRepository {
        val created = mutableListOf<SourceImage>()

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> {
            created += source
            return result
        }

        override suspend fun load(id: String) = Result.Failure(AppError.MissingSource)
        override suspend fun save(document: EditDocument) = Result.Success(Unit)
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


        override suspend fun saveFillResult(
            projectId: String,
            fillId: String,
            bitmap: android.graphics.Bitmap,
        ): Result<com.diffuse.core.imaging.model.ImageRef> =
            Result.Success(com.diffuse.core.imaging.model.ImageRef("/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(
            projectId: String,
            outpaintId: String,
            bitmap: android.graphics.Bitmap,
        ): Result<com.diffuse.core.imaging.model.ImageRef> =
            Result.Success(com.diffuse.core.imaging.model.ImageRef("/outpaint_$outpaintId.png"))

        override suspend fun duplicate(id: String) = Result.Success("copy")
        override suspend fun delete(id: String) = Result.Success(Unit)
    }

    /** The real loader over the real fixtures, so the whole import path is exercised. */
    private fun loader() = ImageLoader(
        RuntimeEnvironment.getApplication().contentResolver,
        object : DispatcherProvider {
            override val default = UnconfinedTestDispatcher()
            override val io = UnconfinedTestDispatcher()
        },
    )

    private fun fixtureUri(name: String): Uri {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "fixtures").isDirectory }
        return Uri.fromFile(java.io.File(java.io.File(root, "fixtures"), name))
    }

    @Test
    fun `a decoded pick becomes a project and opens the editor`() = runTest {
        val repository = RecordingRepository()

        val outcome = BrowseImport(loader(), repository).import(fixtureUri("photo_512.png"))

        assertEquals(BrowseImport.Outcome.OpenEditor("project-1"), outcome)
        assertEquals(1, repository.created.size)
    }

    @Test
    fun `an undecodable pick reports Unsupported instead of creating a project`() = runTest {
        val repository = RecordingRepository()

        val outcome = BrowseImport(loader(), repository).import(fixtureUri("corrupt.jpg"))

        assertEquals(BrowseImport.Outcome.Failed(AppError.Unsupported), outcome)
        assertTrue("no project should be created", repository.created.isEmpty())
    }

    @Test
    fun `a missing pick reports MissingSource`() = runTest {
        val outcome = BrowseImport(loader(), RecordingRepository())
            .import(Uri.fromFile(java.io.File("/nope/gone.png")))

        assertEquals(BrowseImport.Outcome.Failed(AppError.MissingSource), outcome)
    }

    @Test
    fun `every AppError maps to a Korean string`() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            AppError.TooLarge,
            AppError.Unsupported,
            AppError.MissingSource,
            AppError.Io(RuntimeException()),
        ).forEach { error ->
            assertTrue(context.getString(error.messageRes()).isNotBlank())
        }
    }
}
