package com.diffuse.feature.editor.tools.crop

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * T69, specs/crop.md: "opening 자르기 refits to the un-cropped source". A plan that ends in
 * `crop_ratio` commits the `Crop` and then opens the tool, so this is where it showed up — the
 * photo looked squished to the new ratio with a rect sitting on top of it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropPreviewTest {

    private lateinit var repository: RecordingRepository
    private lateinit var renderer: RecordingRenderer
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = RecordingRepository()
        renderer = RecordingRenderer()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening 자르기 on a cropped document renders it without the crop`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Crop)

        assertTrue("a preview should have been requested", renderer.previews.isNotEmpty())
        assertEquals(emptyList<Operation.Crop>(), renderer.previews.last().crops())
    }

    /** The rect was always right; it is the image underneath that was wrong. */
    @Test
    fun `the crop rect the overlay gets still comes from the document`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Crop)

        val rect = viewModel.uiState.value.cropState.rect
        assertEquals(RECT.left, rect.left, TOLERANCE)
        assertEquals(RECT.right, rect.right, TOLERANCE)
    }

    /** Only the `Crop` is dropped: the user frames the photo they actually have. */
    @Test
    fun `every other operation still shows while the sheet is open`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Crop)

        val shown = renderer.previews.last()
        assertEquals(1, shown.adjusts().size)
        assertEquals(AdjustKind.Exposure, shown.adjusts().single().kind)
    }

    @Test
    fun `취소 puts the crop back in the preview and changes nothing else`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Crop)

        viewModel.cancelSheet()

        assertEquals(1, renderer.previews.last().crops().size)
        assertEquals(1, viewModel.uiState.value.document!!.crops().size)
    }

    @Test
    fun `적용 renders the crop it just committed`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Crop)

        viewModel.applySheet()

        assertEquals(1, renderer.previews.last().crops().size)
    }

    /** A tool that is not 자르기 never drops it — this is the crop sheet's rule, not a general one. */
    @Test
    fun `another tool leaves the crop in the preview`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Light)

        assertEquals(1, renderer.previews.last().crops().size)
    }

    // ---- fixtures --------------------------------------------------------

    private fun EditDocument.crops() = operations.filterIsInstance<Operation.Crop>()
    private fun EditDocument.adjusts() = operations.filterIsInstance<Operation.Adjust>()

    private fun viewModel() = EditorViewModel(
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
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    /** Records the document it was asked to draw, which is the whole claim under test. */
    private class RecordingRenderer : Renderer {
        val previews = mutableListOf<EditDocument>()

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap> {
            previews += document
            return Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))
        }

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    /** Loads a document that already carries a `Crop` and an adjust, as a plan's would. */
    private class RecordingRepository : ProjectRepository {
        private var document = EditDocument(
            id = PROJECT_ID,
            source = ImageRef("/p.jpg"),
            createdAt = 0L,
            updatedAt = 0L,
        ).withAdjust(AdjustKind.Exposure, 0.4f).withCrop(RECT, angleDeg = 0f)

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
        ): Result<ImageRef> = Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SIZE = 32
        const val TOLERANCE = 1e-4f
        val RECT = RectF(0.2f, 0.1f, 0.8f, 0.9f)
    }
}
