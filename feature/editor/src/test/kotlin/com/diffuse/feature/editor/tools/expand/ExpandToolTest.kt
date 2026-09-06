package com.diffuse.feature.editor.tools.expand

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeOutpaintProvider
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
import com.diffuse.core.imaging.model.Margins
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/outpaint.md §6, §8. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandToolTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val outpainter = FakeOutpaintProvider()
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
    fun `a key and a clean document open the sheet`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Expand)

        assertEquals(Tool.Expand, viewModel.uiState.value.selectedTool)
        assertEquals(0, outpainter.outpaintCount)
    }

    /** §3: 확대 comes before 선택, because a mask is sized to the un-extended canvas. */
    @Test
    fun `a document holding a mask refuses, and says why`() = runTest {
        val viewModel = withSelection()

        viewModel.onToolClick(Tool.Expand)

        assertEquals(R.string.expand_after_mask, viewModel.uiState.value.expand.message)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `a missing key opens the settings sheet rather than the expand sheet`() = runTest {
        outpainter.setAvailability(Availability.Unavailable(AppError.Invalid("no api key")))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Expand)

        assertEquals(R.string.expand_needs_key, viewModel.uiState.value.expand.message)
        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `an outage that is not a missing key does not throw a sheet at the user`() = runTest {
        outpainter.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Expand)

        assertEquals(R.string.expand_failed, viewModel.uiState.value.expand.message)
        assertFalse(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    // ---- the run ---------------------------------------------------------

    @Test
    fun `적용 with no margins commits nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Expand)

        viewModel.applySheet()

        assertEquals(0, outpainter.outpaintCount)
        assertNull(viewModel.uiState.value.document!!.outpaint())
    }

    @Test
    fun `적용 writes one Outpaint carrying the margins that were dragged`() = runTest {
        val viewModel = withMargins()

        viewModel.applySheet()

        val outpaint = viewModel.uiState.value.document!!.outpaint()!!
        assertEquals(MARGINS, outpaint.margins)
        // The request carries `core:ai`'s own Margins, converted at this module's edge (T64).
        val sent = outpainter.lastMargins!!
        assertEquals(MARGINS, Margins(sent.left, sent.top, sent.right, sent.bottom))
        // The op names the file the repository actually wrote, under the outpaint's own id.
        assertEquals(listOf(outpaint.id), repository.savedOutpaints)
        assertEquals(ImageRef("/p/outpaint_${outpaint.id}.png"), outpaint.resultRef)
        assertEquals(1, outpainter.outpaintCount)
    }

    /** §3: the request is built from the bare source, not from the current preview. */
    @Test
    fun `the image handed to the model is the bare source`() = runTest {
        val viewModel = withMargins()

        viewModel.applySheet()

        assertEquals(SOURCE_SIZE, outpainter.lastImageWidth)
    }

    @Test
    fun `a success closes the sheet and forgets the margins`() = runTest {
        val viewModel = withMargins()

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertTrue(viewModel.uiState.value.expand.margins.isEmpty)
        assertFalse(viewModel.uiState.value.expand.busy)
    }

    @Test
    fun `undo takes the expansion back`() = runTest {
        val viewModel = withMargins()
        viewModel.applySheet()

        viewModel.undo()

        assertNull(viewModel.uiState.value.document!!.outpaint())
    }

    // ---- failures --------------------------------------------------------

    @Test
    fun `a failure leaves the sheet open with the margins intact`() = runTest {
        val viewModel = withMargins()
        outpainter.failNext(AppError.Io(java.io.IOException("offline")))

        viewModel.applySheet()

        assertEquals(Tool.Expand, viewModel.uiState.value.selectedTool)
        assertEquals(MARGINS, viewModel.uiState.value.expand.margins)
        assertEquals(R.string.expand_failed, viewModel.uiState.value.expand.message)
        assertNull(viewModel.uiState.value.document!!.outpaint())
    }

    /** §5's aspect guard and its still-white guard say the same thing to the user. */
    @Test
    fun `an answer at the wrong aspect and one still white read the same`() = runTest {
        val viewModel = withMargins()
        outpainter.failNext(AppError.Unsupported)
        viewModel.applySheet()
        assertEquals(R.string.expand_failed, viewModel.uiState.value.expand.message)

        outpainter.failNext(AppError.Unavailable)
        viewModel.applySheet()
        assertEquals(R.string.expand_failed, viewModel.uiState.value.expand.message)
    }

    @Test
    fun `a blocked generation says the image cannot be edited`() = runTest {
        val viewModel = withMargins()
        outpainter.failNext(AppError.Invalid("blocked:IMAGE_SAFETY"))

        viewModel.applySheet()

        assertEquals(R.string.expand_blocked, viewModel.uiState.value.expand.message)
    }

    @Test
    fun `a failed write leaves the document untouched`() = runTest {
        val viewModel = withMargins()
        repository.failOutpaintWrite = true

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.document!!.outpaint())
        assertEquals(R.string.expand_failed, viewModel.uiState.value.expand.message)
    }

    // ---- cancelling ------------------------------------------------------

    @Test
    fun `cancelling commits nothing and forgets the margins`() = runTest {
        val viewModel = withMargins()

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertTrue(viewModel.uiState.value.expand.margins.isEmpty)
        assertNull(viewModel.uiState.value.document!!.outpaint())
        assertEquals(0, outpainter.outpaintCount)
    }

    @Test
    fun `cancelling the work clears the busy flag without touching the document`() = runTest {
        val viewModel = withMargins()

        viewModel.expand.cancel()

        assertFalse(viewModel.uiState.value.expand.busy)
        assertNull(viewModel.uiState.value.document!!.outpaint())
    }

    // ---- fixtures --------------------------------------------------------

    /** Applies a selection, which is exactly what §3's guard refuses to outpaint over. */
    private suspend fun withSelection(): EditorViewModel {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        viewModel.applySheet()
        return viewModel
    }

    /** …and the ordinary path: open the sheet and drag the edges out. */
    private fun withMargins(): EditorViewModel {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Expand)
        viewModel.expand.setMargins(MARGINS)
        return viewModel
    }

    private fun viewModel() = EditorViewModel(
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(
            segmentation,
            FakeEraseProvider(),
            FakeFillProvider(),
            outpainter,
            FakePlanProvider(),
            FakeSpeechInput(),
            settings,
            geminiSettings,
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    /** The bare source renders wider than the document does, so "which bitmap" is observable. */
    private class FakeRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(
                if (document.operations.isEmpty()) {
                    Bitmap.createBitmap(SOURCE_SIZE, SOURCE_SIZE, Bitmap.Config.ARGB_8888)
                } else {
                    Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888)
                },
            )

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SOURCE_SIZE, SOURCE_SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? =
            document.mask(maskId)?.let { fullFrameMask() }

        private fun fullFrameMask(): Bitmap {
            val bitmap = Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ALPHA_8)
            for (pixel in 0 until PREVIEW_SIZE * PREVIEW_SIZE) {
                bitmap.setPixel(pixel % PREVIEW_SIZE, pixel / PREVIEW_SIZE, OPAQUE shl ALPHA_SHIFT)
            }
            return bitmap
        }
    }

    private class RecordingRepository : ProjectRepository {
        val savedOutpaints = mutableListOf<String>()
        var failOutpaintWrite = false
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
        ): Result<ImageRef> = Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(
            projectId: String,
            outpaintId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> {
            if (failOutpaintWrite) return Result.Failure(AppError.Io(java.io.IOException("full")))
            savedOutpaints += outpaintId
            return Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))
        }

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SOURCE_SIZE = 40
        const val PREVIEW_SIZE = 32
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
        val MARGINS = Margins(left = 0.25f, top = 0.1f, right = 0.25f, bottom = 0.1f)
    }
}
