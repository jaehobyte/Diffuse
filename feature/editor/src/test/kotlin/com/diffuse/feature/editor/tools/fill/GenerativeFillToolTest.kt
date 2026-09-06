package com.diffuse.feature.editor.tools.fill

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/generative_fill.md §6, §9. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerativeFillToolTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val filler = FakeFillProvider()
    private lateinit var repository: RecordingRepository
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = RecordingRepository()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- §6's disabled-state table ---------------------------------------

    @Test
    fun `the tool refuses without a selection and opens no sheet`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Fill)

        assertEquals(R.string.fill_needs_selection, viewModel.uiState.value.fill.message)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(0, filler.fillCount)
    }

    @Test
    fun `a missing key opens the settings sheet rather than the fill sheet`() = runTest {
        filler.setAvailability(Availability.Unavailable(AppError.Invalid("no api key")))
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Fill)

        assertEquals(R.string.fill_needs_key, viewModel.uiState.value.fill.message)
        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `an outage that is not a missing key does not throw a sheet at the user`() = runTest {
        filler.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Fill)

        assertEquals(R.string.fill_failed, viewModel.uiState.value.fill.message)
        assertFalse(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `a selection and a key open the sheet, which is where the noun comes from`() = runTest {
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Fill)

        assertEquals(Tool.Fill, viewModel.uiState.value.selectedTool)
        assertEquals(0, filler.fillCount)
    }

    // ---- the run ---------------------------------------------------------

    @Test
    fun `적용 writes one GenerativeFill naming the selection and the prompt`() = runTest {
        val viewModel = withPrompt()
        val maskId = viewModel.uiState.value.document!!.activeMaskId

        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        val fill = document.generativeFills().single()
        assertEquals(maskId, fill.maskId)
        assertEquals(PROMPT, fill.prompt)
        // The op names the file the repository actually wrote, under the fill's own id.
        assertEquals(listOf(fill.id), repository.savedFills)
        assertEquals(ImageRef("/p/fill_${fill.id}.png"), fill.resultRef)
        assertEquals(1, filler.fillCount)
    }

    /** §6: the fill is not dilated — the region the user chose is the region that changes. */
    @Test
    fun `the mask handed to the model is the user's own selection`() = runTest {
        val viewModel = withPrompt()
        // Read before the run: committing re-renders, which resolves a fresh mask bitmap.
        val selection = viewModel.uiState.value.activeMask

        viewModel.applySheet()

        assertSame(selection, filler.lastMask)
        assertEquals(PROMPT, filler.lastPrompt)
    }

    @Test
    fun `a success closes the sheet and clears the prompt`() = runTest {
        val viewModel = withPrompt()

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals("", viewModel.uiState.value.fill.prompt)
        assertFalse(viewModel.uiState.value.fill.busy)
    }

    @Test
    fun `undo takes the fill back`() = runTest {
        val viewModel = withPrompt()
        viewModel.applySheet()

        viewModel.undo()

        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
    }

    @Test
    fun `the selection stays active, so the next tool still has it`() = runTest {
        val viewModel = withPrompt()
        val maskId = viewModel.uiState.value.document!!.activeMaskId

        viewModel.applySheet()

        assertEquals(maskId, viewModel.uiState.value.document!!.activeMaskId)
    }

    // ---- failures --------------------------------------------------------

    @Test
    fun `a failure leaves the sheet open with the prompt intact`() = runTest {
        val viewModel = withPrompt()
        filler.failNext(AppError.Io(java.io.IOException("offline")))

        viewModel.applySheet()

        assertEquals(Tool.Fill, viewModel.uiState.value.selectedTool)
        assertEquals(PROMPT, viewModel.uiState.value.fill.prompt)
        assertEquals(R.string.fill_failed, viewModel.uiState.value.fill.message)
        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
    }

    @Test
    fun `a blocked generation says the image cannot be edited`() = runTest {
        val viewModel = withPrompt()
        filler.failNext(AppError.Invalid("blocked:IMAGE_SAFETY"))

        viewModel.applySheet()

        assertEquals(R.string.fill_blocked, viewModel.uiState.value.fill.message)
    }

    /** §3's still-white guard reaches the user as "nothing was made", not as a generic failure. */
    @Test
    fun `an answer that came back still white asks for different words`() = runTest {
        val viewModel = withPrompt()
        filler.failNext(AppError.Unavailable)

        viewModel.applySheet()

        assertEquals(R.string.fill_empty, viewModel.uiState.value.fill.message)
        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
    }

    @Test
    fun `a failed write leaves the document untouched`() = runTest {
        val viewModel = withPrompt()
        repository.failFillWrite = true

        viewModel.applySheet()

        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
        assertEquals(R.string.fill_failed, viewModel.uiState.value.fill.message)
    }

    // ---- cancelling ------------------------------------------------------

    @Test
    fun `cancelling the sheet commits nothing and forgets the prompt`() = runTest {
        val viewModel = withPrompt()

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals("", viewModel.uiState.value.fill.prompt)
        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
        assertEquals(0, filler.fillCount)
    }

    @Test
    fun `cancelling the work clears the busy flag without touching the document`() = runTest {
        val viewModel = withPrompt()

        viewModel.fill.cancel()

        assertFalse(viewModel.uiState.value.fill.busy)
        assertTrue(viewModel.uiState.value.document!!.generativeFills().isEmpty())
    }

    // ---- fixtures --------------------------------------------------------

    /** Applies a selection first, which is what the fill consumes. */
    private suspend fun withSelection(): EditorViewModel {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        viewModel.applySheet()
        return viewModel
    }

    /** …and then opens the sheet and types the noun, which is what 적용 sends. */
    private suspend fun withPrompt(): EditorViewModel {
        val viewModel = withSelection()
        viewModel.onToolClick(Tool.Fill)
        viewModel.fill.setPrompt(PROMPT)
        return viewModel
    }

    private fun viewModel() = EditorViewModel(
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(
            segmentation,
            FakeEraseProvider(),
            filler,
            FakePlanProvider(),
            FakeSpeechInput(),
            settings,
            geminiSettings,
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    /** Resolves any mask to a full-frame one, so the fill always has something to work on. */
    private class FakeRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? =
            document.mask(maskId)?.let { fullFrameMask() }

        private fun fullFrameMask(): Bitmap {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
            for (pixel in 0 until SIZE * SIZE) {
                bitmap.setPixel(pixel % SIZE, pixel / SIZE, OPAQUE shl ALPHA_SHIFT)
            }
            return bitmap
        }
    }

    private class RecordingRepository : ProjectRepository {
        val savedFills = mutableListOf<String>()
        var failFillWrite = false
        private var document = EditDocument(
            id = PROJECT_ID,
            source = ImageRef("/p.jpg"),
            createdAt = 0L,
            updatedAt = 0L,
        )

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success(PROJECT_ID)
        override suspend fun load(id: String): Result<EditDocument> = Result.Success(document)
        override suspend fun save(document: EditDocument): Result<Unit> {
            this.document = document
            return Result.Success(Unit)
        }

        override suspend fun saveMask(
            projectId: String,
            maskId: String,
            alpha: Bitmap,
        ): Result<ImageRef> = Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(
            projectId: String,
            eraseId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> = Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(
            projectId: String,
            fillId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> {
            if (failFillWrite) return Result.Failure(AppError.Io(java.io.IOException("full")))
            savedFills += fillId
            return Result.Success(ImageRef("/p/fill_$fillId.png"))
        }

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val PROMPT = "빨간 우산"
        const val SIZE = 32
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
