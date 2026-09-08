package com.diffuse.feature.editor

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
import com.diffuse.core.ai.PortraitDetector
import com.diffuse.core.ai.PortraitResult
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** work/decisions.md T79: what the editor does with the detector, on the way to the menu. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortraitDetectionTest {

    private val detector = FakePortraitDetector()
    private val repository = RecordingRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** §5: once, on the frame the canvas already rendered — never a second decode. */
    @Test
    fun `the source is read once, at preview size`() = runTest {
        viewModel()

        assertEquals(1, detector.detectCount)
        assertEquals(PREVIEW_SIZE, detector.lastImageWidth)
    }

    @Test
    fun `a portrait selects the portrait menu`() = runTest {
        detector.result = PortraitResult.Portrait
        val viewModel = viewModel()

        assertEquals(PortraitResult.Portrait, viewModel.uiState.value.portrait)
        assertEquals(ToolMenuProfile.Portrait, menuProfileFor(viewModel.uiState.value.portrait))
    }

    @Test
    fun `anything else keeps the general menu`() = runTest {
        detector.result = PortraitResult.NotPortrait
        assertEquals(ToolMenuProfile.General, menuProfileFor(viewModel().uiState.value.portrait))

        detector.result = PortraitResult.Unknown
        assertEquals(ToolMenuProfile.General, menuProfileFor(viewModel().uiState.value.portrait))
    }

    /** §11: a detector that cannot answer is silent — the editor's one snackbar stays free. */
    @Test
    fun `an unavailable detector raises no message`() = runTest {
        detector.result = PortraitResult.Unknown
        val state = viewModel().uiState.value

        assertTrue(
            listOf(
                state.selection.message, state.erase.message, state.fill.message,
                state.expand.message, state.auto.message, state.style.message,
            ).all { it == null },
        )
    }

    /** T79's acceptance criterion: the hint never reaches the document. */
    @Test
    fun `detection adds no operation to the document`() = runTest {
        detector.result = PortraitResult.Portrait
        val viewModel = viewModel()

        assertEquals(emptyList<Any>(), viewModel.uiState.value.document?.operations)
        assertEquals(0, repository.saveCount)
    }

    /**
     * §6: a detection asked about an older source cannot land on a newer one, however late it
     * answers. The gate resumes a plain `suspendCoroutine`, so the stale detection genuinely
     * finishes and reaches the guard instead of being swallowed by the job's cancellation — what
     * drops it is the identity check against the source now on screen.
     */
    @Test
    fun `a late answer about an older source is dropped`() = runTest {
        val gated = GatedDetector()
        val viewModel = viewModel(gated)
        val document = requireNotNull(viewModel.uiState.value.document)

        viewModel.renderSource(document)
        gated.answer(0, PortraitResult.Portrait)

        assertEquals(PortraitResult.Unknown, viewModel.uiState.value.portrait)

        gated.answer(1, PortraitResult.NotPortrait)

        assertEquals(PortraitResult.NotPortrait, viewModel.uiState.value.portrait)
    }

    // ---- fixtures --------------------------------------------------------

    private fun viewModel(portrait: PortraitDetector = detector) = EditorViewModel(
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
            portrait,
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

    /** A detector that answers only when the test says so, one call at a time. */
    private class GatedDetector : PortraitDetector {

        private val waiting = mutableListOf<Continuation<PortraitResult>>()

        override suspend fun detect(image: Bitmap): PortraitResult =
            suspendCoroutine { waiting += it }

        fun answer(call: Int, result: PortraitResult) = waiting[call].resume(result)
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
    }
}
