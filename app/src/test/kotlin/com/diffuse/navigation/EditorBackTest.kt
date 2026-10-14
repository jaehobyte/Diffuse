package com.diffuse.navigation

import com.diffuse.feature.editor.tools.enhance.TopazStorage
import com.diffuse.core.data.TopazWorkspace
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.ExportTestTag
import com.diffuse.feature.editor.R as EditorR
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.export.ExportPipeline
import com.diffuse.feature.export.ExportSettingsStore
import com.diffuse.feature.export.ExportSheetTestTag
import com.diffuse.feature.export.Exporter
import com.diffuse.feature.export.ImageStore
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/tool_groups.md §4 and specs/editor_shell.md, through the real wrapper: the export and
 * tool-sheet handlers outrank the AI level, the AI level outranks leaving, and leaving comes last.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class EditorBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: EditorViewModel
    private var leftCount = 0

    private val aiTag get() = compose.activity.getString(EditorR.string.editor_tool_ai)

    /** The top bar's back shares the strip's label, so the AI level is told by its first tool. */
    private val aiLevelTag get() = compose.activity.getString(EditorR.string.editor_tool_select)

    @Before
    fun setUp() {
        viewModel = editorViewModel()
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                EditorWithExport(
                    viewModel = viewModel,
                    exporter = Exporter(ExportPipeline(StubRenderer), NoStore),
                    settingsStore = ExportSettingsStore(compose.activity),
                    onBack = { leftCount++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `back closes the AI level first and leaves the editor on the next press`() {
        compose.onNodeWithTag(aiTag).performClick()
        compose.onNodeWithTag(aiLevelTag).assertExists()

        pressBack()

        assertEquals(0, leftCount)
        compose.onNodeWithTag(aiTag).assertExists()
        compose.onNodeWithTag(aiLevelTag).assertDoesNotExist()

        pressBack()

        assertEquals(1, leftCount)
    }

    @Test
    fun `back cancels an open tool before it closes the AI level`() {
        compose.onNodeWithTag(aiTag).performClick()
        compose.runOnIdle { viewModel.onToolClick(Tool.Light) }
        compose.waitForIdle()

        pressBack()

        assertEquals(0, leftCount)
        assertNull(viewModel.uiState.value.selectedTool)

        pressBack()

        assertEquals(1, leftCount)
    }

    @Test
    fun `back closes the export sheet before the AI level`() {
        compose.onNodeWithTag(aiTag).performClick()
        compose.onNodeWithTag(ExportTestTag).performClick()
        compose.onNodeWithTag(ExportSheetTestTag).assertExists()

        pressBack()

        compose.onNodeWithTag(ExportSheetTestTag).assertDoesNotExist()
        compose.onNodeWithTag(aiLevelTag).assertExists()
        assertEquals(0, leftCount)

        pressBack()

        compose.onNodeWithTag(aiLevelTag).assertDoesNotExist()
        assertEquals(0, leftCount)

        pressBack()

        assertEquals(1, leftCount)
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // ---- fixtures --------------------------------------------------------

    private fun editorViewModel(): EditorViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return EditorViewModel(
            context = context,
            repository = StubRepository(),
            renderer = StubRenderer,
            ai = EditorAi(
                FakeSegmentationProvider(openDelayMs = 0),
                FakeEraseProvider(),
                FakeFillProvider(),
                FakeOutpaintProvider(),
                FakePlanProvider(),
                FakeSpeechInput(),
                Sam3Settings(context),
                GeminiSettings(context),
                FakeMatchStyleProvider(),
                com.diffuse.core.ai.FakeTopazProvider(),
                com.diffuse.core.ai.topaz.TopazSettings(context),
            ),
            dispatchers = ImmediateDispatchers,
            savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
            topazStorage = TopazStorage(
                TopazWorkspace(java.io.File(context.cacheDir, "topaz_test"), ImmediateDispatchers),
            ) { _, _, _, _ -> com.diffuse.core.common.Result.Success(Unit) },
        )
    }

    private object ImmediateDispatchers : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private object StubRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit
        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    /** Export is never run here; the exporter only has to exist. */
    private object NoStore : ImageStore {
        override suspend fun write(bitmap: Bitmap, format: com.diffuse.feature.export.ExportFormat) =
            error("not exported in this test")
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

        override suspend fun saveMask(projectId: String, maskId: String, alpha: Bitmap) =
            Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(projectId: String, eraseId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(projectId: String, fillId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(projectId: String, outpaintId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun saveSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
            result: Bitmap,
            support: Bitmap,
        ) = Result.Success(
            SkinRetouchFiles(ImageRef("/p/retouch_$retouchId.png"), ImageRef("/p/mask_$maskId.png")),
        )

        override suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String) =
            Result.Success(Unit)

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap) =
            Result.Success(ImageRef("/p/shot_$fileId.png"))

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>) =
            Result.Success(Unit)

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SIZE = 32
    }
}
