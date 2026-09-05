package com.diffuse.core.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.db.ProjectDatabase
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.render.Renderer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: ProjectDatabase
    private lateinit var files: ProjectFiles
    private var now = 1_000L

    private val fakeRenderer = object : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888))
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ProjectDatabase::class.java,
        ).allowMainThreadQueries().build()
        files = ProjectFiles(temp.newFolder())
    }

    /** Built inside the test so it shares `runTest`'s scheduler. */
    private fun TestScope.repository() = DefaultProjectRepository(
        dao = database.projectDao(),
        files = files,
        renderer = fakeRenderer,
        dispatchers = object : DispatcherProvider {
            override val default = StandardTestDispatcher(testScheduler)
            override val io = StandardTestDispatcher(testScheduler)
        },
        clock = { now },
    )

    @After
    fun tearDown() = database.close()

    private fun sourceImage(hasAlpha: Boolean = false) = SourceImage(
        bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888),
        widthPx = 120,
        heightPx = 90,
        sourceWidthPx = 120,
        sourceHeightPx = 90,
        mimeType = if (hasAlpha) "image/png" else "image/jpeg",
        hasAlpha = hasAlpha,
    )

    private suspend fun TestScope.createProject(repository: DefaultProjectRepository): String {
        val created = repository.create(sourceImage())
        assertTrue("create failed: $created", created is Result.Success)
        return (created as Result.Success).value
    }

    @Test
    fun `create writes the source and document, and registers a row`() = runTest {
        val repository = repository()
        val id = createProject(repository)

        assertTrue("source missing", files.findSource(id)?.isFile == true)
        assertTrue("document missing", files.documentFile(id).isFile)
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun `save then load round-trips the document`() = runTest {
        val repository = repository()
        val id = createProject(repository)
        val loaded = repository.load(id)
        val edited = (loaded as Result.Success).value.withAdjust(AdjustKind.Exposure, 0.5f)

        repository.save(edited)
        val reloaded = (repository.load(id) as Result.Success).value

        assertEquals(0.5f, reloaded.adjustValue(AdjustKind.Exposure), 0f)
        assertEquals(edited.operations, reloaded.operations)
    }

    @Test
    fun `a successful save leaves no temporary file behind`() = runTest {
        val repository = repository()
        val id = createProject(repository)
        repository.save((repository.load(id) as Result.Success).value)

        val leftovers = files.projectDir(id).listFiles().orEmpty().filter {
            it.name.endsWith(".tmp")
        }
        assertTrue("found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `observeAll returns the newest first`() = runTest {
        val repository = repository()
        now = 1_000L
        val older = createProject(repository)
        now = 5_000L
        val newer = createProject(repository)

        val ids = repository.observeAll().first().map { it.id }

        assertEquals(listOf(newer, older), ids)
    }

    @Test
    fun `delete removes the row and the folder`() = runTest {
        val repository = repository()
        val id = createProject(repository)

        repository.delete(id)

        assertEquals(0, repository.observeAll().first().size)
        assertFalse("folder survived", files.projectDir(id).exists())
    }

    @Test
    fun `duplicate copies the folder under a new id`() = runTest {
        val repository = repository()
        val id = createProject(repository)

        val copyId = (repository.duplicate(id) as Result.Success).value

        assertTrue(copyId != id)
        assertEquals(2, repository.observeAll().first().size)
        assertTrue(files.documentFile(copyId).isFile)
    }

    @Test
    fun `loading a project that does not exist fails rather than throwing`() = runTest {
        val result = repository().load("nope")

        assertTrue(result is Result.Failure)
    }

    @Test
    fun `writeAtomically never leaves a partial file at the target`() {
        val target = File(temp.newFolder(), "document.json")

        runCatching {
            files.writeAtomically(target) { error("boom while writing") }
        }

        assertFalse("a failed write must not create the target", target.exists())
    }
}
