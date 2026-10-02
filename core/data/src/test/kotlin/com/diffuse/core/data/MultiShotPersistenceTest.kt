package com.diffuse.core.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.db.ProjectDatabase
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.EditDocumentJson
import com.diffuse.core.imaging.model.HeroMask
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.Timeline
import com.diffuse.core.imaging.model.TimelineLayout
import com.diffuse.core.imaging.model.withMultiShot
import com.diffuse.core.imaging.render.CpuRenderer
import com.diffuse.core.imaging.render.MultiShotLayout
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

/**
 * specs/multishot.md §7, §9: the subjects' files — written, discarded, refused when broken or gone,
 * copied with the project — and the same pixels through apply, undo, redo, save, load and export
 * with the production renderer.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiShotPersistenceTest {

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
    fun `a subject is written as an RGBA PNG in the project folder`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()

        val ref = repository.saveShotSubject(id, "f1", subject()).value()

        assertEquals(files.shotFile(id, "f1").absolutePath, ref.path)
        val decoded = BitmapFactory.decodeFile(ref.path)
        assertEquals(0, Color.alpha(decoded.getPixel(WIDTH - 1, 0)))
        assertEquals(RED, decoded.getPixel(10, 10))
        assertFalse(File(ref.path + ".tmp").exists())
    }

    @Test
    fun `a write that fails leaves nothing behind`() = runTest {
        val repository = repository()
        // A file where the project folder should be makes every write under it fail.
        files.projectDir("blocked").apply { parentFile?.mkdirs() }.writeText("not a folder")

        val saved = repository.saveShotSubject("blocked", "f1", subject())

        assertTrue(saved is Result.Failure && saved.error is AppError.Io)
        assertFalse(files.shotFile("blocked", "f1").exists())
    }

    @Test
    fun `discard deletes this session's files but never one the saved document references`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val committed = repository.saveShotSubject(id, "kept", subject()).value()
        val abandoned = repository.saveShotSubject(id, "gone", subject()).value()
        repository.save(repository.load(id).value().withMultiShot(listOf(shot("a", committed)))).value()

        repository.discardShotSubjects(id, listOf("kept", "gone")).value()

        assertTrue(File(committed.path).isFile)
        assertFalse(File(abandoned.path).exists())
    }

    @Test
    fun `apply, undo, redo, save, load and export show the same pixels`() = runTest {
        val repository = repository()
        val renderer = renderer()
        val id = repository.create(sourceImage()).value()
        val maskRef = repository.saveMask(id, "m", leftHalfMask()).value()
        val base = repository.load(id).value()
            .withAdjust(AdjustKind.Exposure, 0.2f)
            .withMask(maskRef, "m")
            .withAdjust(AdjustKind.Contrast, 0.3f, maskId = "m")
        val history = HistoryStack(base)
        val ref = repository.saveShotSubject(id, "f1", subject()).value()

        history.push(base.withMultiShot(listOf(shot("a", ref))).withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f))
        val applied = full(renderer, history.current.value)
        history.undo()
        val undone = full(renderer, history.current.value)
        history.redo()
        val redone = full(renderer, history.current.value)
        repository.save(history.current.value).value()
        val reloaded = repository.load(id).value()
        val exported = full(renderer, reloaded)

        assertFalse(applied.sameAs(undone))
        assertTrue(applied.sameAs(redone))
        assertTrue(applied.sameAs(exported))
        assertEquals("m", reloaded.activeMaskId)
        assertEquals(history.current.value.operations, reloaded.operations)
    }

    @Test
    fun `a missing subject refuses the load and a corrupt one fails the render`() = runTest {
        val repository = repository()
        val renderer = renderer()
        val id = repository.create(sourceImage()).value()
        val ref = repository.saveShotSubject(id, "f1", subject()).value()
        repository.save(repository.load(id).value().withMultiShot(listOf(shot("a", ref)))).value()

        File(ref.path).writeBytes(byteArrayOf(1, 2, 3))
        val corrupt = repository.load(id).value()
        assertEquals(Result.Failure(AppError.MissingSource), renderer.full(corrupt))

        File(ref.path).delete()
        assertEquals(Result.Failure(AppError.MissingSource), repository.load(id))
    }

    @Test
    fun `a broken composite on disk is refused rather than partly restored`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val ref = repository.saveShotSubject(id, "f1", subject()).value()
        repository.save(repository.load(id).value().withMultiShot(listOf(shot("a", ref)))).value()
        val text = files.documentFile(id).readText()

        files.documentFile(id).writeText(text.replace(Regex("\"scale\":[0-9.]+"), "\"scale\":9.0"))

        assertEquals(Result.Failure(AppError.Unsupported), repository.load(id))
    }

    @Test
    fun `a duplicate owns its source and subjects and outlives the original`() = runTest {
        val repository = repository()
        val renderer = renderer()
        val id = repository.create(sourceImage()).value()
        val ref = repository.saveShotSubject(id, "f1", subject()).value()
        repository.save(repository.load(id).value().withMultiShot(listOf(shot("a", ref)))).value()
        val original = full(renderer, repository.load(id).value())

        val copyId = repository.duplicate(id).value()
        val copied = EditDocumentJson.decode(files.documentFile(copyId).readText())
        assertEquals(files.shotFile(copyId, "f1").absolutePath, copied.multiShot()!!.shots.single().subjectRef.path)
        assertEquals(files.findSource(copyId)!!.absolutePath, copied.source.path)

        repository.delete(id).value()
        val reopened = repository.load(copyId).value()
        assertTrue(original.sameAs(full(renderer, reopened)))
    }

    // ---- the time layout's hero mask (§6, §7) ---------------------------------------------------

    @Test
    fun `five even afterimages keep the hero through save, load, export and duplicate`() = runTest {
        val repository = repository()
        val renderer = renderer()
        val id = repository.create(sourceImage()).value()
        val subjects = (1..5).map { repository.saveShotSubject(id, "f$it", subject()).value() }
        val hero = repository.saveShotSubject(id, "hero", heroMask()).value()
        val document = timed(repository.load(id).value(), subjects, hero)
        repository.save(document).value()
        val saved = full(renderer, document)

        val reloaded = repository.load(id).value()
        assertEquals(document.operations, reloaded.operations)
        assertTrue(saved.sameAs(full(renderer, reloaded)))
        // The hero square's input pixels are not covered by the five afterimages.
        val input = full(renderer, document.copy(operations = emptyList()))
        assertEquals(input.getPixel(WIDTH / 2, HEIGHT / 2), saved.getPixel(WIDTH / 2, HEIGHT / 2))

        val copyId = repository.duplicate(id).value()
        val copied = EditDocumentJson.decode(files.documentFile(copyId).readText()).multiShot()!!
        assertEquals(files.shotFile(copyId, "hero").absolutePath, copied.timeline!!.hero!!.ref.path)
        repository.delete(id).value()
        assertTrue(saved.sameAs(full(renderer, repository.load(copyId).value())))
    }

    @Test
    fun `discard never deletes the hero mask the saved document references`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val subjects = listOf(repository.saveShotSubject(id, "f1", subject()).value())
        val hero = repository.saveShotSubject(id, "hero", heroMask()).value()
        val abandoned = repository.saveShotSubject(id, "hero2", heroMask()).value()
        repository.save(timed(repository.load(id).value(), subjects, hero)).value()

        repository.discardShotSubjects(id, listOf("f1", "hero", "hero2")).value()

        assertTrue(File(hero.path).isFile)
        assertFalse(File(abandoned.path).exists())
    }

    @Test
    fun `a hero mask that is gone or of another size refuses the load`() = runTest {
        val repository = repository()
        val id = repository.create(sourceImage()).value()
        val subjects = listOf(repository.saveShotSubject(id, "f1", subject()).value())
        val hero = repository.saveShotSubject(id, "hero", heroMask()).value()
        repository.save(timed(repository.load(id).value(), subjects, hero)).value()

        Bitmap.createBitmap(WIDTH / 2, HEIGHT / 2, Bitmap.Config.ARGB_8888).let { smaller ->
            File(hero.path).outputStream().use { smaller.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(Result.Failure(AppError.Unsupported), repository.load(id))

        File(hero.path).delete()
        assertEquals(Result.Failure(AppError.MissingSource), repository.load(id))
    }

    // ---- fixtures --------------------------------------------------------

    /** Up to four afterimages in the time layout with a hero square in the middle of the canvas. */
    private fun timed(base: EditDocument, subjects: List<ImageRef>, hero: ImageRef): EditDocument {
        val shots = subjects.mapIndexed { index, ref ->
            shot("s$index", ref).copy(anchor = NormPoint(0.25f, 0.5f))
        }
        val anchor = NormPoint(0.5f, 0.75f)
        val layout = TimelineLayout()
        val placed = MultiShotLayout.faded(MultiShotLayout.positioned(shots, anchor, layout, WIDTH, HEIGHT), 1f)
        return base.withMultiShot(
            placed,
            mode = MultiShotMode.Timeline,
            timeline = Timeline(placed.map { it.id }, layout, HeroMask(hero, WIDTH, HEIGHT, anchor)),
        )
    }

    /** The middle of the canvas, as the alpha of an opaque bitmap — how 추출 완료 writes a hero. */
    private fun heroMask(): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        for (y in HEIGHT / 4 until HEIGHT * 3 / 4) for (x in WIDTH / 4 until WIDTH * 3 / 4) setPixel(x, y, Color.BLACK)
    }

    private suspend fun full(renderer: CpuRenderer, document: EditDocument): Bitmap =
        renderer.full(document).value()

    private fun shot(id: String, ref: ImageRef) = Shot(
        id = id,
        subjectRef = ref,
        widthPx = WIDTH,
        heightPx = HEIGHT,
        placement = ShotPlacement(offsetX = 0.1f, scale = 1.2f, rotationDeg = 10f, opacity = 0.6f),
    )

    /** A red square on a transparent frame, like an extracted subject. */
    private fun subject(): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until HEIGHT / 2) for (x in 0 until WIDTH / 2) setPixel(x, y, RED)
    }

    private fun leftHalfMask(): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ALPHA_8).apply {
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH / 2) setPixel(x, y, 255 shl 24)
    }

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
        val RED = Color.rgb(255, 0, 0)
    }
}
