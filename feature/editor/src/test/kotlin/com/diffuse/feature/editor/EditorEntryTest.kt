package com.diffuse.feature.editor

import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.feature.editor.tools.enhance.testTopazSettings
import com.diffuse.feature.editor.tools.enhance.testTopazStorage
import android.graphics.Bitmap
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
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.model.RetouchKind
import com.diffuse.core.imaging.model.SkinRetouchSettings
import com.diffuse.core.imaging.render.Renderer
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
 * work/decisions.md D092: entering the editor renders the preview and the bare source, and nothing
 * else — there is no face detection behind the menu any more. A project that already carries a
 * 피부 보정 keeps it through further edits.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorEntryTest {

    private val repository = RecordingRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** specs/editor_shell.md: compare still needs the source, at the same size as the preview. */
    @Test
    fun `entering renders the preview and the source at preview size`() = runTest {
        val state = viewModel().uiState.value

        assertEquals(PREVIEW_SIZE, state.preview?.width)
        assertEquals(PREVIEW_SIZE, state.source?.width)
    }

    @Test
    fun `entering adds no operation, saves nothing and raises no message`() = runTest {
        val state = viewModel().uiState.value

        assertEquals(emptyList<Any>(), state.document?.operations)
        assertEquals(0, repository.saveCount)
        assertTrue(
            listOf(
                state.selection.message, state.erase.message, state.fill.message,
                state.expand.message, state.style.message,
            ).all { it == null },
        )
    }

    @Test
    fun `디테일 opens and applies on entry like any adjust tool`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Detail)
        viewModel.onAdjust(AdjustKind.Sharpen, SHARPEN)
        viewModel.onAdjustFinished()

        assertEquals(Tool.Detail, viewModel.uiState.value.selectedTool)

        viewModel.applySheet()

        val state = viewModel.uiState.value
        assertEquals(null, state.selectedTool)
        assertEquals(
            listOf(SHARPEN),
            state.document?.operations
                ?.filterIsInstance<Operation.Adjust>()
                ?.filter { it.kind == AdjustKind.Sharpen }
                ?.map { it.value },
        )
    }

    /** D092: a stored 피부 보정 is replayed, not re-run — and a later edit leaves it in place. */
    @Test
    fun `an existing 피부 보정 survives a later edit`() = runTest {
        repository.document = repository.document.withSkinRetouch(
            maskRef = ImageRef("/p/mask_s.png"),
            resultRef = ImageRef("/p/retouch_r.png"),
            settings = SkinRetouchSettings(
                strengths = RetouchKind.entries.associateWith { 0f } + (RetouchKind.Blemish to 1f),
                engines = mapOf(RetouchKind.Blemish to "engine@1"),
            ),
            maskId = "s",
            id = "r",
        )
        val retouches = repository.document.skinRetouches()
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Light)
        viewModel.onAdjust(AdjustKind.Exposure, EXPOSURE)
        viewModel.onAdjustFinished()
        viewModel.applySheet()

        val document = requireNotNull(viewModel.uiState.value.document)
        assertEquals(retouches, document.skinRetouches())
        assertEquals(ImageRef("/p/mask_s.png"), document.mask("s")?.maskRef)
    }

    // ---- fixtures --------------------------------------------------------

    private fun viewModel() = EditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(
            FakeSegmentationProvider(openDelayMs = 0),
            FakeEraseProvider(),
            FakeFillProvider(),
            FakeOutpaintProvider(),
            FakePlanProvider(),
            FakeSpeechInput(),
            Sam3Settings(ApplicationProvider.getApplicationContext()),
            GeminiSettings(ApplicationProvider.getApplicationContext()),
            FakeMatchStyleProvider(),
            FakeTopazProvider(),
            testTopazSettings(),
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
        topazStorage = testTopazStorage(),
    )

    private class FakeRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(
                Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888),
            )

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(
                Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888),
            )

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        var saveCount: Int = 0
            private set

        var document = EditDocument(
            id = PROJECT_ID,
            source = ImageRef("/p.jpg"),
            createdAt = 0L,
            updatedAt = 0L,
        )

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success(PROJECT_ID)
        override suspend fun load(id: String): Result<EditDocument> = Result.Success(document)
        override suspend fun save(document: EditDocument): Result<Unit> {
            saveCount++
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
        const val PROJECT_ID = "p"
        const val PREVIEW_SIZE = 32
        const val SHARPEN = 0.4f
        const val EXPOSURE = 0.2f
    }
}
