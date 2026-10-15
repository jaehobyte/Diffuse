package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
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
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolStripTestTag
import com.diffuse.feature.editor.canvas.CanvasCaptionTestTag
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
 * 지시 as the Photo Editor's assistant works: tapping it listens, with no sheet, and the sheet
 * opens on what was heard with the plan already asked for. Robolectric grants no runtime
 * permissions, so the mic permission is granted in the manifest the test app uses — the
 * launcher path is `VoicePromptBar`'s, exercised by its own tests.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class DirectDictationTest {

    @get:Rule
    val compose = createComposeRule()

    private val provider = FakeSegmentationProvider(openDelayMs = 0)
    private val planner = FakePlanProvider()
    private val speech = FakeSpeechInput()
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
        grantMic()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tapping 지시 listens instead of opening the sheet`() {
        val viewModel = viewModel()
        show(viewModel)

        tapDirect()

        assertEquals(1, speech.starts)
        assertNull("no sheet", viewModel.uiState.value.selectedTool)
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.direct_listening)).assertExists()
        assertEquals(0, planner.planCount)
    }

    @Test
    fun `what is heard so far is shown on the canvas`() {
        val viewModel = viewModel()
        show(viewModel)
        tapDirect()

        speech.emit(SpeechState.Listening(partial = "나무를"))
        compose.waitForIdle()

        compose.onNodeWithText("나무를").assertExists()
    }

    @Test
    fun `a final result opens the sheet with the plan already asked for`() {
        val viewModel = viewModel()
        show(viewModel)
        tapDirect()

        speech.emit(SpeechState.Final(REQUEST))
        compose.waitForIdle()

        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        compose.onNodeWithTag(DirectSheetTestTag).assertExists()
        assertEquals(REQUEST, viewModel.uiState.value.direct.request)
        assertEquals(1, planner.planCount)
        assertEquals(REQUEST, planner.lastRequest?.text)
        compose.onNodeWithTag(CanvasCaptionTestTag).assertDoesNotExist()
    }

    @Test
    fun `a failure says so and opens nothing`() {
        val viewModel = viewModel()
        show(viewModel)
        tapDirect()

        speech.emit(SpeechState.Failed(AppError.Unavailable))
        compose.waitForIdle()

        assertNull(viewModel.uiState.value.selectedTool)
        assertNotNull(viewModel.uiState.value.direct.message)
        assertEquals(0, planner.planCount)
        compose.onNodeWithText(string(R.string.direct_listening)).assertDoesNotExist()
    }

    @Test
    fun `a second tap while listening stops it and submits nothing`() {
        val viewModel = viewModel()
        show(viewModel)
        tapDirect()

        tapDirect()

        assertEquals(1, speech.stops)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(0, planner.planCount)
        compose.onNodeWithText(string(R.string.direct_listening)).assertDoesNotExist()
    }

    @Test
    fun `without a recogniser 지시 opens its sheet as before`() {
        speech.isAvailable = false
        val viewModel = viewModel()
        show(viewModel)

        tapDirect()

        assertEquals(0, speech.starts)
        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        compose.onNodeWithTag(DirectSheetTestTag).assertExists()
    }

    /** specs/vibe_edit.md §10: a planner that cannot work opens the 서버 설정 sheet, not the mic. */
    @Test
    fun `without a working planner 지시 still opens the settings sheet`() {
        planner.setAvailability(Availability.Unavailable(AppError.Invalid("no key")))
        val viewModel = viewModel()
        show(viewModel)

        tapDirect()

        assertEquals(0, speech.starts)
        assertTrue(viewModel.uiState.value.selection.showSettings)
    }

    // ---- fixtures --------------------------------------------------------

    private fun show(viewModel: EditorViewModel) {
        compose.setContent { EditorRoute(onExport = {}, viewModel = viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag(string(R.string.editor_tool_ai)).performClick()
        compose.waitForIdle()
    }

    /** The strip is a `LazyRow`, and 지시 is its last item: scroll it in before tapping. */
    private fun tapDirect() {
        val label = string(R.string.editor_tool_direct)
        compose.onNodeWithTag(ToolStripTestTag).performScrollToNode(hasTestTag(label))
        compose.onNodeWithTag(label).performClick()
        compose.waitForIdle()
    }

    private fun grantMic() {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
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
            planner,
            speech,
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
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
    }
}
