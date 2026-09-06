package com.diffuse.feature.editor.tools.erase

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeEraseProvider
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/generative_erase.md §5, §8, §9. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerativeEraseToolTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val eraser = FakeEraseProvider()
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

    // ---- §9, one per row -------------------------------------------------

    @Test
    fun `the tool refuses without a selection and says so`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_needs_selection, viewModel.uiState.value.erase.message)
        assertEquals(0, eraser.eraseCount)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
        assertFalse(viewModel.uiState.value.selection.showSettings)
    }

    @Test
    fun `a missing key opens the settings sheet rather than only a snackbar`() = runTest {
        eraser.setAvailability(Availability.Unavailable(AppError.Invalid("no api key")))
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_needs_key, viewModel.uiState.value.erase.message)
        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertEquals(0, eraser.eraseCount)
    }

    @Test
    fun `a blocked generation says the image cannot be edited`() = runTest {
        val viewModel = withSelection()
        eraser.failNext(AppError.Invalid("blocked:IMAGE_SAFETY"))

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_blocked, viewModel.uiState.value.erase.message)
        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
    }

    @Test
    fun `any other failure says it could not erase`() = runTest {
        val viewModel = withSelection()
        eraser.failNext(AppError.Unavailable)

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_failed, viewModel.uiState.value.erase.message)
    }

    @Test
    fun `an Invalid that is not a block is still the generic failure`() = runTest {
        val viewModel = withSelection()
        eraser.failNext(AppError.Invalid("mask must be the image's size"))

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_failed, viewModel.uiState.value.erase.message)
    }

    @Test
    fun `an outage that is not a missing key does not throw a sheet at the user`() = runTest {
        eraser.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Erase)

        assertEquals(R.string.erase_failed, viewModel.uiState.value.erase.message)
        assertFalse(viewModel.uiState.value.selection.showSettings)
    }

    @Test
    fun `the selection survives a failure, so a retry costs no re-selection`() = runTest {
        val viewModel = withSelection()
        val maskId = viewModel.uiState.value.document!!.activeMaskId
        eraser.failNext(AppError.Invalid("blocked:SAFETY"))

        viewModel.onToolClick(Tool.Erase)
        viewModel.onToolClick(Tool.Erase)

        assertEquals(maskId, viewModel.uiState.value.document!!.activeMaskId)
        assertEquals(1, viewModel.uiState.value.document!!.generativeErases().size)
    }

    @Test
    fun `running it writes one GenerativeErase op referencing the active mask`() = runTest {
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Erase)

        val document = viewModel.uiState.value.document!!
        val erase = document.generativeErases().single()
        assertEquals(document.activeMaskId, erase.maskId)
        assertEquals(1, eraser.eraseCount)
        assertEquals(listOf(erase.id), repository.savedErases)
    }

    @Test
    fun `undo takes the erase back`() = runTest {
        val viewModel = withSelection()
        viewModel.onToolClick(Tool.Erase)

        viewModel.undo()

        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
    }

    @Test
    fun `a failed run leaves the document untouched and reports it`() = runTest {
        val viewModel = withSelection()
        eraser.failNext(AppError.Unavailable)

        viewModel.onToolClick(Tool.Erase)

        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
        assertNotNull(viewModel.uiState.value.erase.message)
    }

    @Test
    fun `a failed write leaves the document untouched`() = runTest {
        val viewModel = withSelection()
        repository.failEraseWrite = true

        viewModel.onToolClick(Tool.Erase)

        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
        assertNotNull(viewModel.uiState.value.erase.message)
    }

    @Test
    fun `an unavailable provider is reported through availability, not a crash`() = runTest {
        eraser.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = withSelection()

        assertFalse(viewModel.uiState.value.erase.enabled)
    }

    @Test
    fun `cancelling clears the busy flag without touching the document`() = runTest {
        val viewModel = withSelection()

        viewModel.erase.cancel()

        assertFalse(viewModel.uiState.value.erase.busy)
        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
    }

    // ---- fixtures --------------------------------------------------------

    /** Applies a selection first, which is what the eraser consumes. */
    private suspend fun withSelection(): EditorViewModel {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        viewModel.applySheet()
        return viewModel
    }

    private fun viewModel() = EditorViewModel(
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(segmentation, eraser, FakeSpeechInput(), settings, geminiSettings),
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    /** Resolves any mask to a full-frame one, so the eraser always has something to work on. */
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
        val savedErases = mutableListOf<String>()
        var failEraseWrite = false
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
        ): Result<ImageRef> {
            if (failEraseWrite) return Result.Failure(AppError.Io(java.io.IOException("full")))
            savedErases += eraseId
            return Result.Success(ImageRef("/p/erase_$eraseId.png"))
        }

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SIZE = 32
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
