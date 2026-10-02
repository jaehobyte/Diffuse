package com.diffuse.core.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.db.ProjectDatabase
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocumentJson
import com.diffuse.core.imaging.model.RetouchKind
import com.diffuse.core.imaging.model.SkinRetouchSettings
import com.diffuse.core.imaging.render.CpuRenderer
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

/** specs/skin_retouch_pipeline.md §6: the retouch's files, saved, refused, copied and deleted. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinRetouchPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: ProjectDatabase
    private lateinit var files: ProjectFiles

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
    fun `saveSkinRetouch writes the result and its support atomically`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()

        val saved = repository.saveSkinRetouch(id, "r", "s", result(), support()).value()

        assertEquals(files.retouchFile(id, "r").absolutePath, saved.resultRef.path)
        assertEquals(files.maskFile(id, "s").absolutePath, saved.maskRef.path)
        assertEquals(FILL, BitmapFactory.decodeFile(saved.resultRef.path).getPixel(1, 1))
        val mask = MaskIo.read(File(saved.maskRef.path))!!
        assertEquals(255, mask.getPixel(1, 1) ushr 24)
        assertEquals(0, mask.getPixel(WIDTH - 1, 1) ushr 24)
        val leftovers = files.projectDir(id).listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `a failed second write removes the first file and reports Io`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        // A non-empty directory where the support belongs: neither rename nor copy can replace it.
        val blocker = files.maskFile(id, "s").apply { mkdirs() }
        File(blocker, "occupied").writeText("x")

        val saved = repository.saveSkinRetouch(id, "r", "s", result(), support())

        assertTrue("expected Io, got $saved", (saved as? Result.Failure)?.error is AppError.Io)
        assertFalse("the result file survived", files.retouchFile(id, "r").exists())
        assertTrue("a file this call did not write was removed", File(blocker, "occupied").isFile)
    }

    @Test
    fun `discard deletes only files the saved document does not reference`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val committed = repository.saveSkinRetouch(id, "r1", "s1", result(), support()).value()
        repository.save(
            repository.load(id).value().withSkinRetouch(
                committed.maskRef,
                committed.resultRef,
                settings(),
                maskId = "s1",
                id = "r1",
            ),
        ).value()
        val pending = repository.saveSkinRetouch(id, "r2", "s2", result(), support()).value()

        repository.discardSkinRetouch(id, "r1", "s1").value()
        repository.discardSkinRetouch(id, "r2", "s2").value()

        assertTrue(File(committed.resultRef.path).isFile)
        assertTrue(File(committed.maskRef.path).isFile)
        assertFalse(File(pending.resultRef.path).exists())
        assertFalse(File(pending.maskRef.path).exists())
    }

    @Test
    fun `load refuses a retouch whose result or support file is gone`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val saved = commitRetouch(repository, id)

        File(saved.resultRef.path).delete()
        assertEquals(Result.Failure(AppError.MissingSource), repository.load(id))

        repository.saveSkinRetouch(id, "r", "s", result(), support()).value()
        File(saved.maskRef.path).delete()
        assertEquals(Result.Failure(AppError.MissingSource), repository.load(id))
    }

    @Test
    fun `load refuses a retouch with invalid strengths`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        commitRetouch(repository, id)

        val text = files.documentFile(id).readText()
        files.documentFile(id).writeText(text.replace("\"blemish\":1.0", "\"blemish\":1.5"))

        assertTrue(text.contains("\"blemish\":1.0"))
        assertEquals(Result.Failure(AppError.Unsupported), repository.load(id))
    }

    @Test
    fun `a duplicate owns its retouch files and outlives the original`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        commitRetouch(repository, id)

        val copyId = repository.duplicate(id).value()
        val copied = EditDocumentJson.decode(files.documentFile(copyId).readText())
        val retouch = copied.skinRetouches().single()
        val supportRef = copied.mask(retouch.maskId)!!.maskRef

        assertEquals(files.retouchFile(copyId, "r").absolutePath, retouch.resultRef.path)
        assertEquals(files.maskFile(copyId, "s").absolutePath, supportRef.path)
        // specs/multishot.md §7 widened the rewrite: the source moves to the copy's folder too.
        assertEquals(files.findSource(copyId)!!.absolutePath, copied.source.path)

        repository.delete(id).value()
        val reloaded = repository.load(copyId).value()
        assertTrue(File(reloaded.skinRetouches().single().resultRef.path).isFile)
        assertTrue(File(reloaded.mask("s")!!.maskRef.path).isFile)
    }

    @Test
    fun `delete removes the retouch files with the folder`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val saved = commitRetouch(repository, id)

        repository.delete(id).value()

        assertFalse(File(saved.resultRef.path).exists())
        assertFalse(File(saved.maskRef.path).exists())
    }

    @Test
    fun `a saved retouch renders its stored pixels inside the support after reopening`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val renderer = renderer()
        val before = (renderer.full(repository.load(id).value()) as Result.Success).value
        commitRetouch(repository, id)

        val reopened = repository.load(id).value()
        val output = (renderer.full(reopened) as Result.Success).value

        assertEquals(WIDTH, output.width)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val expected = if (x < WIDTH / 2) FILL else before.getPixel(x, y)
                assertEquals("($x, $y)", expected, output.getPixel(x, y))
            }
        }
    }

    // ---- fixtures --------------------------------------------------------

    private suspend fun commitRetouch(repository: DefaultProjectRepository, id: String): SkinRetouchFiles {
        val saved = repository.saveSkinRetouch(id, "r", "s", result(), support()).value()
        repository.save(
            repository.load(id).value().withSkinRetouch(
                saved.maskRef,
                saved.resultRef,
                settings(),
                maskId = "s",
                id = "r",
            ),
        ).value()
        return saved
    }

    private fun settings() = SkinRetouchSettings(
        strengths = RetouchKind.entries.associateWith { 0f } + (RetouchKind.Blemish to 1f),
        engines = mapOf(RetouchKind.Blemish to "engine@1"),
    )

    private fun TestScope.dispatchers() = object : DispatcherProvider {
        override val default = StandardTestDispatcher(testScheduler)
        override val io = StandardTestDispatcher(testScheduler)
    }

    private fun TestScope.renderer() = CpuRenderer(
        ImageLoader(ApplicationProvider.getApplicationContext<Application>().contentResolver, dispatchers()),
        dispatchers(),
    )

    private fun TestScope.repository() = DefaultProjectRepository(
        dao = database.projectDao(),
        files = files,
        renderer = renderer(),
        dispatchers = dispatchers(),
        clock = { 1_000L },
    )

    private fun result(): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(FILL) }

    private fun support(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ALPHA_8)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                bitmap.setPixel(x, y, if (x < WIDTH / 2) 255 shl 24 else 0)
            }
        }
        return bitmap
    }

    private fun sourceImage(): SourceImage {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) bitmap.setPixel(x, y, Color.rgb(x * 2, y * 2, 90))
        }
        return SourceImage(
            bitmap = bitmap,
            widthPx = WIDTH,
            heightPx = HEIGHT,
            sourceWidthPx = WIDTH,
            sourceHeightPx = HEIGHT,
            hasAlpha = false,
            mimeType = "image/jpeg",
        )
    }

    private fun <T> Result<T>.value(): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        const val WIDTH = 120
        const val HEIGHT = 90
        val FILL = Color.rgb(20, 160, 90)
    }
}
