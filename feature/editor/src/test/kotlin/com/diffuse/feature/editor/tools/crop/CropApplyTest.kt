package com.diffuse.feature.editor.tools.crop

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.Result
import com.diffuse.core.data.AUTOSAVE_DEBOUNCE_MS
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.CpuRenderer
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.enhance.testTopazSettings
import com.diffuse.feature.editor.tools.enhance.testTopazStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * work/REVIEW.md R1: 적용 wrote the UI state it had read *before* the push back over the one the
 * history collector had just published, so the screen, Undo, export and the save on leaving all
 * kept the previous crop. These change the crop to a new, asymmetric one and check every place
 * the committed edit has to show up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropApplyTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `적용 commits a new crop to the document, Undo and the preview`() = immediate {
        val repository = RecordingRepository(bare())
        val renderer = RecordingRenderer()
        val viewModel = viewModel(repository, renderer)

        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        assertCrop(NEW_RECT, state.document!!)
        assertTrue("one crop is one undo", state.canUndo)
        assertFalse(state.canRedo)
        assertCrop(NEW_RECT, renderer.previews.last())
        assertEquals(state.document, state.renderedDocument)
    }

    @Test
    fun `적용 replaces an existing crop in place and keeps the other operations`() = immediate {
        val repository = RecordingRepository(cropped())
        val viewModel = viewModel(repository, RecordingRenderer())

        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        val operations = viewModel.uiState.value.document!!.operations
        assertEquals(2, operations.size)
        assertTrue(operations[0] is Operation.Adjust)
        assertCrop(NEW_RECT, viewModel.uiState.value.document!!)
    }

    @Test
    fun `one undo takes the new crop back and redo restores it`() = immediate {
        val viewModel = viewModel(RecordingRepository(cropped()), RecordingRenderer())
        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        viewModel.undo()
        assertCrop(OLD_RECT, viewModel.uiState.value.document!!)

        viewModel.redo()
        assertCrop(NEW_RECT, viewModel.uiState.value.document!!)
    }

    @Test
    fun `reopening after 적용 shows the new crop and 취소 keeps it`() = immediate {
        val renderer = RecordingRenderer()
        val viewModel = viewModel(RecordingRepository(cropped()), renderer)
        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        viewModel.onToolClick(Tool.Crop)
        assertRect(NEW_RECT, viewModel.uiState.value.cropState.rect)
        viewModel.cancelSheet()

        assertCrop(NEW_RECT, viewModel.uiState.value.document!!)
        assertCrop(NEW_RECT, renderer.previews.last())
    }

    @Test
    fun `another tool opened after 적용 keeps the new crop`() = immediate {
        val viewModel = viewModel(RecordingRepository(cropped()), RecordingRenderer())
        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        viewModel.onToolClick(Tool.Light)
        viewModel.cancelSheet()

        assertCrop(NEW_RECT, viewModel.uiState.value.document!!)
    }

    @Test
    fun `취소 drops the draft and restores the committed crop`() = immediate {
        val renderer = RecordingRenderer()
        val viewModel = viewModel(RecordingRepository(cropped()), renderer)

        cropTo(viewModel, NEW_RECT)
        viewModel.cancelSheet()

        assertCrop(OLD_RECT, viewModel.uiState.value.document!!)
        assertCrop(OLD_RECT, renderer.previews.last())
        assertFalse(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `the full frame at 0 degrees removes the crop`() = immediate {
        val renderer = RecordingRenderer()
        val viewModel = viewModel(RecordingRepository(cropped()), renderer)

        cropTo(viewModel, RectF(0f, 0f, 1f, 1f))
        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        assertTrue(document.operations.none { it is Operation.Crop })
        assertEquals(1, document.operations.size)
        assertTrue(renderer.previews.last().operations.none { it is Operation.Crop })
        assertTrue(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `a 90 degree turn with a tilt is committed as one angle`() = immediate {
        val viewModel = viewModel(RecordingRepository(bare()), RecordingRenderer())
        viewModel.onToolClick(Tool.Crop)
        viewModel.onCropChange(viewModel.uiState.value.cropState.rotated(1).straightened(TILT))

        viewModel.applySheet()

        val crop = viewModel.uiState.value.document!!.operations.filterIsInstance<Operation.Crop>().single()
        assertEquals(QUARTER + TILT, crop.angleDeg, TOLERANCE)
    }

    /** The collector order the device does not have: the history publish runs later. */
    @Test
    fun `적용 holds when the history collector runs after the sheet closes`() = queued {
        val renderer = RecordingRenderer()
        val viewModel = viewModel(RecordingRepository(cropped()), renderer)
        advanceUntilIdle()
        viewModel.onToolClick(Tool.Crop)
        advanceUntilIdle()
        viewModel.onCropChange(viewModel.uiState.value.cropState.copy(rect = RectF(NEW_RECT)))

        viewModel.applySheet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        assertCrop(NEW_RECT, state.document!!)
        assertTrue(state.canUndo)
        assertCrop(NEW_RECT, renderer.previews.last())
        assertEquals(state.document, state.renderedDocument)
    }

    /** Leaving straight after 적용, before any collector has run, still saves what was applied. */
    @Test
    fun `leaving before the queued collector runs saves the new crop`() = queued {
        val repository = RecordingRepository(cropped())
        val viewModel = viewModel(repository, RecordingRenderer())
        advanceUntilIdle()
        viewModel.onToolClick(Tool.Crop)
        advanceUntilIdle()
        viewModel.onCropChange(viewModel.uiState.value.cropState.copy(rect = RectF(NEW_RECT)))

        viewModel.applySheet()
        viewModel.onLeave()

        assertCrop(NEW_RECT, repository.saved!!)
    }

    /** A render still in flight when 적용 lands, or when the sheet is reopened, must not win. */
    @Test
    fun `a suspended render does not overwrite the committed preview`() = immediate {
        val renderer = RecordingRenderer()
        val viewModel = viewModel(RecordingRepository(cropped()), renderer)
        cropTo(viewModel, NEW_RECT)
        val gate = CompletableDeferred<Unit>()
        renderer.gate = gate

        viewModel.applySheet()
        viewModel.onToolClick(Tool.Crop)
        viewModel.cancelSheet()
        renderer.gate = null
        gate.complete(Unit)

        val state = viewModel.uiState.value
        assertCrop(NEW_RECT, state.document!!)
        assertEquals(state.document, state.renderedDocument)
    }

    @Test
    fun `autosave, leaving, stopping and reloading all keep the new crop`() = immediate {
        val repository = RecordingRepository(cropped())
        val viewModel = viewModel(repository, RecordingRenderer())
        cropTo(viewModel, NEW_RECT)
        viewModel.applySheet()

        advanceTimeBy(AUTOSAVE_DEBOUNCE_MS + 1)
        assertCrop(NEW_RECT, repository.saved!!)

        viewModel.onLeave()
        assertCrop(NEW_RECT, repository.saved!!)
        viewModel.onStop()
        assertCrop(NEW_RECT, repository.saved!!)

        val reloaded = viewModel(repository, RecordingRenderer())
        assertCrop(NEW_RECT, reloaded.uiState.value.document!!)
    }

    /**
     * The real renderer, on a 3:2 source whose left half is red and right half blue: the crop
     * sits wholly in the blue half, so the frame has the crop's pixel shape and no red in it.
     */
    @Test
    fun `the real renderer draws the committed crop's shape and content`() = immediate {
        val repository = RecordingRepository(bare(source = halves()))
        val renderer = cpuRenderer()
        val viewModel = viewModel(repository, renderer)
        cropTo(viewModel, BLUE_RECT)
        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        val full = (renderer.full(document) {} as Result.Success).value
        assertEquals(BLUE_WIDTH, full.width)
        assertEquals(BLUE_HEIGHT, full.height)
        assertBlue(full)

        val preview = viewModel.uiState.value.preview!!
        assertEquals(BLUE_WIDTH.toFloat() / BLUE_HEIGHT, preview.width.toFloat() / preview.height, ASPECT_TOLERANCE)
        assertEquals(document, viewModel.uiState.value.renderedDocument)

        viewModel.onLeave()
        val exported = (renderer.full(repository.saved!!) {} as Result.Success).value
        assertEquals(BLUE_WIDTH, exported.width)
        assertEquals(BLUE_HEIGHT, exported.height)
    }

    /** The button the user taps, through `EditorRoute`, into the ViewModel. */
    @Test
    fun `tapping 9 to 16 and 적용 in the editor commits that crop`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = RecordingRepository(bare(source = halves()))
        val renderer = cpuRenderer()
        val viewModel = viewModel(repository, renderer)
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()

        compose.onNodeWithTag(string(Tool.Crop.labelRes)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("9:16").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("적용").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
        val crop = viewModel.uiState.value.document!!.operations.filterIsInstance<Operation.Crop>().single()
        val pixelAspect = crop.rect.width() * SOURCE_WIDTH / (crop.rect.height() * SOURCE_HEIGHT)
        assertEquals(NINE_SIXTEEN, pixelAspect, ASPECT_TOLERANCE)
        val full = (runBlocking { renderer.full(viewModel.uiState.value.document!!) {} } as Result.Success).value
        assertEquals(NINE_SIXTEEN, full.width.toFloat() / full.height, ASPECT_TOLERANCE)
        assertTrue(viewModel.uiState.value.canUndo)
    }

    // ---- fixtures --------------------------------------------------------

    private fun immediate(block: suspend TestScope.() -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runTest { block() }
    }

    private fun queued(block: suspend TestScope.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher())
        runTest { block() }
    }

    private fun cropTo(viewModel: EditorViewModel, rect: RectF) {
        viewModel.onToolClick(Tool.Crop)
        viewModel.onCropChange(viewModel.uiState.value.cropState.copy(rect = RectF(rect)))
    }

    private fun assertCrop(expected: RectF, document: EditDocument) {
        val crops = document.operations.filterIsInstance<Operation.Crop>()
        assertEquals("exactly one crop", 1, crops.size)
        assertRect(expected, crops.single().rect)
    }

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    private fun assertBlue(bitmap: Bitmap) {
        val xs = listOf(0, bitmap.width / 2, bitmap.width - 1)
        val ys = listOf(0, bitmap.height / 2, bitmap.height - 1)
        xs.forEach { x ->
            ys.forEach { y ->
                val pixel = bitmap.getPixel(x, y)
                assertTrue("($x,$y) should be blue", Color.blue(pixel) > HIGH && Color.red(pixel) < LOW)
            }
        }
    }

    private fun string(res: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    private fun bare(source: ImageRef = ImageRef("/p.jpg")) =
        EditDocument(id = PROJECT_ID, source = source, createdAt = 0L, updatedAt = 0L)
            .withAdjust(AdjustKind.Exposure, EXPOSURE)

    private fun cropped() = bare().withCrop(OLD_RECT, angleDeg = 0f)

    /** A 3:2 source, red on the left half and blue on the right. */
    private fun halves(): ImageRef {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        for (x in 0 until SOURCE_WIDTH) {
            val color = if (x < SOURCE_WIDTH / 2) Color.RED else Color.BLUE
            for (y in 0 until SOURCE_HEIGHT) bitmap.setPixel(x, y, color)
        }
        val file = File(temp.newFolder(), "halves.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.absolutePath)
    }

    private fun cpuRenderer() = CpuRenderer(
        ImageLoader(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            TestDispatchers,
        ),
        TestDispatchers,
    )

    private fun viewModel(repository: ProjectRepository, renderer: Renderer) = EditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        repository = repository,
        renderer = renderer,
        ai = EditorAi(
            FakeSegmentationProvider(openDelayMs = 0),
            FakeEraseProvider(),
            FakeFillProvider(),
            FakeOutpaintProvider(),
            FakePlanProvider(),
            FakeSpeechInput(),
            settings,
            geminiSettings,
            FakeMatchStyleProvider(),
            FakeTopazProvider(),
            testTopazSettings(),
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
        topazStorage = testTopazStorage(),
    )

    /** Records what it was asked to draw; a set [gate] holds every render until completed. */
    private class RecordingRenderer : Renderer {
        val previews = mutableListOf<EditDocument>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap> {
            previews += document
            gate?.await()
            return Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))
        }

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    /** Loads the last document saved, so a second ViewModel is the reload. */
    private class RecordingRepository(initial: EditDocument) : ProjectRepository {
        var saved: EditDocument? = null
        private var document = initial

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success(PROJECT_ID)
        override suspend fun load(id: String): Result<EditDocument> = Result.Success(document)
        override suspend fun save(document: EditDocument): Result<Unit> {
            this.document = document
            saved = document
            return Result.Success(Unit)
        }

        override suspend fun saveMask(projectId: String, maskId: String, alpha: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(projectId: String, eraseId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(projectId: String, fillId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(
            projectId: String,
            outpaintId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> = Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun saveSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
            result: Bitmap,
            support: Bitmap,
        ): Result<SkinRetouchFiles> = Result.Success(
            SkinRetouchFiles(ImageRef("/p/retouch_$retouchId.png"), ImageRef("/p/mask_$maskId.png")),
        )

        override suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String): Result<Unit> =
            Result.Success(Unit)

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/shot_$fileId.png"))

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>): Result<Unit> =
            Result.Success(Unit)

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SIZE = 32
        const val TOLERANCE = 1e-4f
        const val ASPECT_TOLERANCE = 0.02f
        const val EXPOSURE = 0.4f
        const val QUARTER = 90f
        const val TILT = 5f
        const val SOURCE_WIDTH = 1200
        const val SOURCE_HEIGHT = 800
        const val NINE_SIXTEEN = 9f / 16f
        const val HIGH = 200
        const val LOW = 60

        /** Visibly smaller and off-centre, so it cannot be mistaken for either other crop. */
        val NEW_RECT = RectF(0.55f, 0.1f, 0.9f, 0.6f)
        val OLD_RECT = RectF(0.2f, 0.1f, 0.8f, 0.9f)

        /** x 0.6..0.95 of 1200 = 420px, y 0.1..0.7 of 800 = 480px — all in the blue half. */
        val BLUE_RECT = RectF(0.6f, 0.1f, 0.95f, 0.7f)
        const val BLUE_WIDTH = 420
        const val BLUE_HEIGHT = 480
    }
}
