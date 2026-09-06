package com.diffuse.core.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.db.ProjectDatabase
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/edit_model.md and selection_tool.md §6: an applied mask outlives the session. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: ProjectDatabase
    private lateinit var files: ProjectFiles

    private val fakeRenderer = object : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ProjectDatabase::class.java,
        ).allowMainThreadQueries().build()
        files = ProjectFiles(temp.newFolder())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `saveMask writes mask_id png inside the project folder`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()

        val ref = repository.saveMask(id, "a", mask()).value()

        assertEquals(File(files.projectDir(id), "mask_a.png").absolutePath, ref.path)
        assertTrue(File(ref.path).isFile)
    }

    @Test
    fun `a saved mask round-trips through the document and the file`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val ref = repository.saveMask(id, "a", mask()).value()

        repository.save(repository.load(id).value().withMask(ref, id = "a")).value()
        val reloaded = repository.load(id).value()

        assertEquals("a", reloaded.activeMaskId)
        assertEquals(ref, reloaded.activeMask()?.maskRef)
        val read = MaskIo.read(File(ref.path))!!
        assertEquals(SIZE, read.width)
        assertEquals(255, read.getPixel(1, 1) ushr 24)
        assertEquals(0, read.getPixel(SIZE - 1, 1) ushr 24)
    }

    @Test
    fun `a document whose activeMaskId names no op refuses to load`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val document = repository.load(id).value()

        repository.save(document.copy(activeMaskId = "gone")).value()

        assertEquals(Result.Failure(AppError.Unsupported), repository.load(id))
    }

    private fun TestScope.repository() = DefaultProjectRepository(
        dao = database.projectDao(),
        files = files,
        renderer = fakeRenderer,
        dispatchers = object : DispatcherProvider {
            override val default = StandardTestDispatcher(testScheduler)
            override val io = StandardTestDispatcher(testScheduler)
        },
        clock = { 1_000L },
    )

    private fun mask(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, if (x < SIZE / 2) 255 shl 24 else 0)
            }
        }
        return bitmap
    }

    private fun sourceImage() = SourceImage(
        bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888),
        widthPx = 120,
        heightPx = 90,
        sourceWidthPx = 120,
        sourceHeightPx = 90,
        hasAlpha = false,
        mimeType = "image/jpeg",
    )

    private fun <T> Result<T>.value(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val SIZE = 16
    }
}
