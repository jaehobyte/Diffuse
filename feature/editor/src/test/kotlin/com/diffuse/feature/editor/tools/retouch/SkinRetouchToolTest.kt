package com.diffuse.feature.editor.tools.retouch

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.FakeAutoEnhanceProvider
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakePortraitDetector
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.PortraitResult
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * specs/skin_retouch.md §4: what 피부 보정 does to the editor while there is no engine behind it,
 * which is nothing. Opening and closing it is the whole of the tool today, so this is the test
 * that the whole of it is free of side effects.
 *
 * It says nothing about retouch quality — no pixel is corrected here, and none can be until an
 * engine and the save/render path exist (skin_retouch_validation.md §1).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinRetouchToolTest {

    private val detector = FakePortraitDetector(PortraitResult.Portrait)
    private val repository = RecordingRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tapping 피부 보정 selects it and changes nothing else`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.SkinRetouch)

        val state = viewModel.uiState.value
        assertEquals(Tool.SkinRetouch, state.selectedTool)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertNull(state.document?.activeMaskId)
        assertFalse(state.canUndo)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `cancelling closes the sheet and writes no history`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.SkinRetouch)

        viewModel.cancelSheet()

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
    }

    /** specs/editor_shell.md: tapping the open tool again is the strip's own dismiss. */
    @Test
    fun `tapping it again closes the sheet`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.SkinRetouch)

        viewModel.onToolClick(Tool.SkinRetouch)

        assertNull(viewModel.uiState.value.selectedTool)
    }

    /** Entering and leaving leaves the adjustments the user already made exactly as they were. */
    @Test
    fun `the existing adjustments survive a visit`() = runTest {
        val viewModel = viewModel()
        viewModel.onAdjust(AdjustKind.Sharpen, SHARPEN)
        viewModel.onAdjustFinished()
        val before = viewModel.uiState.value.document

        viewModel.onToolClick(Tool.SkinRetouch)
        viewModel.cancelSheet()

        assertEquals(before, viewModel.uiState.value.document)
        // One undo takes the one adjustment back out, which is only true if the visit pushed
        // nothing of its own on top of it.
        viewModel.undo()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
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
            MonetSettings(ApplicationProvider.getApplicationContext()),
            FakeAutoEnhanceProvider(),
            FakeMatchStyleProvider(),
            detector,
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
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

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        var saveCount: Int = 0
            private set

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
        const val PREVIEW_SIZE = 32
        const val SHARPEN = 0.5f
    }
}
