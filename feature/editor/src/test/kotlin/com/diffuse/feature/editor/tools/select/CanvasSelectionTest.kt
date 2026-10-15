package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
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
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.canvas.CanvasCaptionTestTag
import com.diffuse.feature.editor.canvas.EditorCanvasTestTag
import com.diffuse.feature.editor.tools.enhance.testTopazSettings
import com.diffuse.feature.editor.tools.enhance.testTopazStorage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Selecting straight from the preview: at the AI level of the strip the photo itself takes the
 * tap, no sheet opens, and the mask the tap makes is committed to the document as it lands.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class CanvasSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val provider = FakeSegmentationProvider(openDelayMs = 0)
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening the AI level sends nothing to the backend and says what a tap does`() {
        val viewModel = viewModel()
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag(CanvasCaptionTestTag).assertDoesNotExist()

        compose.onNodeWithTag(string(R.string.editor_tool_ai)).performClick()
        compose.waitForIdle()

        assertEquals("no upload until the user taps or speaks", 0, provider.openCount)
        assertNull("opening the level selects no tool", viewModel.uiState.value.selectedTool)
        compose.onNodeWithTag(CanvasCaptionTestTag).assertExists()
        compose.onNodeWithText(string(R.string.select_hint)).assertExists()
    }

    @Test
    fun `a tap on the photo at the AI level commits that selection without a sheet`() {
        val viewModel = viewModel()
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag(string(R.string.editor_tool_ai)).performClick()
        compose.waitForIdle()

        tapCanvas()

        val state = viewModel.uiState.value
        assertNull("no sheet opens", state.selectedTool)
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
        assertEquals(listOf(true), state.selection.labels)
        assertNotNull(state.selection.mask)
        assertEquals(1, state.document!!.operations.filterIsInstance<Operation.Mask>().size)
        assertNotNull(state.document.activeMaskId)
        assertEquals("the tap opened the session", 1, provider.openCount)
        compose.onNodeWithTag(SelectionOverlayTestTag).assertExists()
        compose.onNodeWithTag(CanvasCaptionTestTag).assertDoesNotExist()
    }

    @Test
    fun `a tap on the photo at the root level still pans and selects nothing`() {
        val viewModel = viewModel()
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()

        tapCanvas()

        assertNull(viewModel.uiState.value.selectedTool)
        assertTrue(viewModel.uiState.value.selection.points.isEmpty())
        assertTrue(viewModel.uiState.value.document!!.operations.isEmpty())
    }

    @Test
    fun `a tap on the photo with no server explains itself instead of selecting`() {
        provider.setAvailability(Availability.Unavailable(AppError.Invalid("not configured")))
        val viewModel = viewModel()
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag(string(R.string.editor_tool_ai)).performClick()
        compose.waitForIdle()

        tapCanvas()

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        assertTrue(state.selection.showSettings)
        assertEquals(0, provider.openCount)
    }

    // ---- fixtures --------------------------------------------------------

    /** `detectTapGestures` holds a single tap for the double-tap window before reporting it. */
    private fun tapCanvas() {
        compose.onNodeWithTag(EditorCanvasTestTag).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_WINDOW_MS)
        compose.waitForIdle()
    }

    private fun string(res: Int) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    private fun viewModel() = EditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        repository = StubRepository(),
        renderer = StubRenderer(),
        ai = EditorAi(
            provider,
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

    private class StubRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit
        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class StubRepository : ProjectRepository {
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

        override suspend fun saveMask(projectId: String, maskId: String, alpha: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(projectId: String, eraseId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(projectId: String, fillId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveEnhanced(projectId: String, fileId: String, source: SourceImage): Result<ImageRef> =
            Result.Success(ImageRef("/p/enhanced_$fileId.jpg"))

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
        override suspend fun discardSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
        ): Result<Unit> = Result.Success(Unit)

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/shot_$fileId.png"))

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>): Result<Unit> =
            Result.Success(Unit)

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p1"
        const val SIZE = 64
        const val DOUBLE_TAP_WINDOW_MS = 1_000L
    }
}
