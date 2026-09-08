package com.diffuse.feature.editor.tools.auto

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.FakeAutoEnhanceProvider
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
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

/** specs/auto_enhance.md §6, §8, §9. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoToolTest {

    private val enhancer = FakeAutoEnhanceProvider()
    private lateinit var repository: RecordingRepository
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings
    private lateinit var monetSettings: MonetSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = RecordingRepository()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        monetSettings = MonetSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- §6's disabled-state table ---------------------------------------

    /** §6: no sheet **before** the call. Tapping runs, and the sheet opens on the result. */
    @Test
    fun `tapping runs and opens the sheet on a result`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Auto)

        assertEquals(1, enhancer.enhanceCount)
        assertEquals(Tool.Auto, viewModel.uiState.value.selectedTool)
        assertEquals(FakeAutoEnhanceProvider.REASON, viewModel.uiState.value.auto.reason)
        assertEquals(
            FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced),
            viewModel.uiState.value.auto.plan,
        )
    }

    @Test
    fun `a blank address opens the 서버 설정 sheet rather than calling`() = runTest {
        enhancer.setAvailability(Availability.Unavailable(AppError.Invalid("no server address")))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Auto)

        assertEquals(R.string.auto_needs_server, viewModel.uiState.value.auto.message)
        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(0, enhancer.enhanceCount)
    }

    @Test
    fun `a failed probe says so and opens nothing`() = runTest {
        enhancer.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Auto)

        assertEquals(R.string.auto_unreachable, viewModel.uiState.value.auto.message)
        assertFalse(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(0, enhancer.enhanceCount)
    }

    /** §6's last row: an answer we could make nothing of is a different sentence from an outage. */
    @Test
    fun `an answer naming no operation we know leaves the sheet closed`() = runTest {
        val viewModel = viewModel()
        enhancer.failNext(AppError.Unsupported)

        viewModel.onToolClick(Tool.Auto)

        assertEquals(R.string.auto_failed, viewModel.uiState.value.auto.message)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    // ---- the commit ------------------------------------------------------

    @Test
    fun `적용 writes one history entry holding every adjustment`() = runTest {
        val viewModel = withResult()

        viewModel.applySheet()

        val plan = FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced)
        assertEquals(plan, viewModel.adjustments())
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `one undo removes the whole boost`() = runTest {
        val viewModel = withResult()
        viewModel.applySheet()

        viewModel.undo()

        // One step out and one step back: the whole boost is a single entry in both directions.
        assertEquals(emptyMap<AdjustKind, Float>(), viewModel.adjustments())
        viewModel.redo()
        assertEquals(
            FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced),
            viewModel.adjustments(),
        )
    }

    /** §9: MonetGPT has no regional edits, so nothing it returns carries a mask. */
    @Test
    fun `the boost is unmasked and leaves activeMaskId alone`() = runTest {
        val viewModel = withResult()

        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        assertNull(document.activeMaskId)
        assertTrue(document.adjusts().all { it.maskId == null })
    }

    // ---- the two controls ------------------------------------------------

    @Test
    fun `강도 scales what is committed and costs no second call`() = runTest {
        val viewModel = withResult()

        viewModel.auto.setIntensity(HALF)
        viewModel.applySheet()

        val plan = FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced)
        assertEquals(plan.mapValues { (_, value) -> value / 2f }, viewModel.adjustments())
        assertEquals(1, enhancer.enhanceCount)
    }

    @Test
    fun `changing the chip costs a call and replaces the plan`() = runTest {
        val viewModel = withResult()

        viewModel.auto.setStyle(AutoStyle.Retro)

        assertEquals(2, enhancer.enhanceCount)
        assertEquals(AutoStyle.Retro, enhancer.lastStyle)
        assertEquals(
            FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Retro),
            viewModel.uiState.value.auto.plan,
        )
        // The sheet never closed, and the slider went back to full strength for a new answer.
        assertEquals(Tool.Auto, viewModel.uiState.value.selectedTool)
        assertEquals(AUTO_INTENSITY_MAX, viewModel.uiState.value.auto.intensity)
    }

    /** §6: the second call is on the photograph, never on the boosted preview it replaced. */
    @Test
    fun `the chip re-runs on the same image the first call was given`() = runTest {
        val viewModel = withResult()

        viewModel.auto.setStyle(AutoStyle.Vibrant)

        assertEquals(PREVIEW_SIZE, enhancer.lastImageWidth)
    }

    // ---- cancelling ------------------------------------------------------

    @Test
    fun `cancelling commits nothing and forgets the plan`() = runTest {
        val viewModel = withResult()

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(emptyMap<AdjustKind, Float>(), viewModel.adjustments())
        assertTrue(viewModel.uiState.value.auto.plan.isEmpty())
    }

    @Test
    fun `a failure leaves the previous chip's result on the sheet`() = runTest {
        val viewModel = withResult()
        enhancer.failNext(AppError.Unavailable)

        viewModel.auto.setStyle(AutoStyle.Vibrant)

        assertEquals(Tool.Auto, viewModel.uiState.value.selectedTool)
        assertEquals(R.string.auto_unreachable, viewModel.uiState.value.auto.message)
        assertEquals(
            FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced),
            viewModel.uiState.value.auto.plan,
        )
    }

    // ---- fixtures --------------------------------------------------------

    private fun withResult(): EditorViewModel = viewModel().also { it.onToolClick(Tool.Auto) }

    private fun EditorViewModel.adjustments(): Map<AdjustKind, Float> =
        uiState.value.document!!.adjusts().associate { it.kind to it.value }

    private fun EditDocument.adjusts(): List<Operation.Adjust> =
        operations.filterIsInstance<Operation.Adjust>()

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
            settings,
            geminiSettings,
            monetSettings,
            enhancer,
            FakeMatchStyleProvider(),
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
        ): Result<ImageRef> = Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val PREVIEW_SIZE = 32
        const val HALF = AUTO_INTENSITY_MAX / 2
    }
}
