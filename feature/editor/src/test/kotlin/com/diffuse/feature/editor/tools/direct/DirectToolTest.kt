package com.diffuse.feature.editor.tools.direct

import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.feature.editor.tools.enhance.testTopazSettings
import com.diffuse.feature.editor.tools.enhance.testTopazStorage
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
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
import com.diffuse.feature.editor.tools.crop.AspectPreset
import kotlinx.coroutines.CompletableDeferred
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

/** specs/vibe_edit.md §3, §9, §10, §12. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectToolTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0).apply { textInstances = 1 }
    private val eraser = FakeEraseProvider()
    private val filler = FakeFillProvider()
    private val planner = FakePlanProvider()
    private val renderer = DirectFakeRenderer()
    private lateinit var repository: DirectRecordingRepository
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = DirectRecordingRepository()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- §10, availability ------------------------------------------------

    @Test
    fun `a blank key greys the tool and tapping it opens the settings sheet`() = runTest {
        planner.setAvailability(Availability.Unavailable(AppError.Invalid("no api key")))
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.direct.enabled)

        viewModel.onToolClick(Tool.Direct)

        assertEquals(R.string.direct_needs_key, viewModel.uiState.value.direct.message?.res)
        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(0, planner.planCount)
    }

    @Test
    fun `with a key the tool opens its sheet`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Direct)

        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertFalse(viewModel.uiState.value.selection.showSettings)
    }

    // ---- §3, the plan -----------------------------------------------------

    @Test
    fun `submitting asks for a plan and leaves the document untouched`() = runTest {
        val viewModel = opened()

        viewModel.direct.submit(REQUEST)

        assertEquals(1, planner.planCount)
        assertEquals(FakePlanProvider.DEFAULT_PLAN, viewModel.uiState.value.direct.plan)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `an empty plan shows the hint and leaves 적용 disabled`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(emptyList()))

        viewModel.direct.submit(REQUEST)

        val state = viewModel.uiState.value.direct
        assertTrue(state.notUnderstood)
        assertNull(state.plan)
        assertFalse(state.canApply)
        assertNull(viewModel.uiState.value.direct.message)
    }

    @Test
    fun `a plan validate rejects is the same hint`() = runTest {
        val viewModel = opened()
        // §9.1: a masked adjust with no selection anywhere cannot run.
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, PlanTarget.New("n1")))))

        viewModel.direct.submit(REQUEST)

        assertTrue(viewModel.uiState.value.direct.notUnderstood)
        assertNull(viewModel.uiState.value.direct.plan)
    }

    @Test
    fun `a second submit replaces the first plan`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)
        val replacement = EditPlan(
            listOf(PlanStep.Select("하늘", "n1"), PlanStep.Adjust(AdjustKind.Exposure, 0.1f, PlanTarget.New("n1"))),
        )
        planner.next(replacement)

        viewModel.direct.submit("하늘을 선택해줘")

        assertEquals(replacement, viewModel.uiState.value.direct.plan)
        assertEquals(2, planner.planCount)
    }

    @Test
    fun `취소 discards the plan and touches nothing`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.direct.plan)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `a blocked planning call is its own message`() = runTest {
        val viewModel = opened()
        planner.failNext(AppError.Invalid("blocked:SAFETY"))

        viewModel.direct.submit(REQUEST)

        assertEquals(R.string.direct_blocked, viewModel.uiState.value.direct.message?.res)
        assertNull(viewModel.uiState.value.direct.plan)
    }

    @Test
    fun `any other planning failure says it could not apply`() = runTest {
        val viewModel = opened()
        planner.failNext(AppError.Unavailable)

        viewModel.direct.submit(REQUEST)

        assertEquals(R.string.direct_failed, viewModel.uiState.value.direct.message?.res)
    }

    // ---- §18, preview then apply --------------------------------------------

    @Test
    fun `실행 builds a candidate off the document without a confirmation, and 적용 commits it once`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)

        // §19: the plan's own selection is found and used; nothing waits for a tap.
        viewModel.applySheet()

        // Reviewed, not committed: the document and its history are untouched.
        val candidate = viewModel.uiState.value.direct.candidate!!
        assertEquals(CandidatePhase.Review, candidate.phase)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
        assertFalse(viewModel.uiState.value.canUndo)

        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        assertEquals(2, document.operations.size)
        assertNotNull(document.activeMaskId)
        assertEquals(
            document.activeMaskId,
            document.operations.filterIsInstance<Operation.Adjust>().single().maskId,
        )
        // One entry for the whole plan: one undo returns to the input.
        viewModel.undo()
        assertEquals(0, viewModel.uiState.value.document!!.operations.size)
        assertFalse(viewModel.uiState.value.canUndo)
    }

    // ---- §4.1, §19 the plan's crop is in the reviewed candidate --------------

    @Test
    fun `a plan that ends with a crop shows it in the candidate and applies it without opening 자르기`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Crop(CropRatio.Story9x16))))
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()
        val reviewed = viewModel.uiState.value.direct.candidate!!.document!!.crop()!!
        assertTrue("a 9:16 crop is taller than it is wide", reviewed.rect.width() < reviewed.rect.height())
        viewModel.applySheet()

        assertNull("no 자르기 sheet is required", viewModel.uiState.value.selectedTool)
        assertEquals(reviewed, viewModel.uiState.value.document!!.crop())
        // The manual tool still opens on the applied rect for a different framing.
        viewModel.onToolClick(Tool.Crop)
        assertEquals(Tool.Crop, viewModel.uiState.value.selectedTool)
        assertEquals(reviewed.rect, viewModel.uiState.value.cropState.rect)
    }

    @Test
    fun `a plan with no crop leaves every tool closed`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.4f))))
        viewModel.direct.submit(REQUEST)

        viewModel.previewAndApply()

        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `a run that stops before its crop opens no tool`() = runTest {
        val viewModel = opened()
        // "없음" is the fake's phrase that finds nothing, so the run stops at step 0.
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("없음", "n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, 0.1f, PlanTarget.New("n1")),
                    PlanStep.Crop(CropRatio.Story9x16),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        // The Select failed, so nothing ran: the sheet stays open on the proposal, no 자르기.
        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.direct.candidate)
        assertNull(viewModel.uiState.value.document!!.crop())
    }

    @Test
    fun `the sheet closes once the candidate is applied`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)

        viewModel.previewAndApply()

        assertNull(viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.direct.plan)
        assertFalse(viewModel.uiState.value.direct.running)
    }

    @Test
    fun `a run containing a Select clears the selection session`() = runTest {
        val viewModel = opened()
        // A tap on the photo opened a session earlier, so one is being held.
        viewModel.selection.open(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        assertNotNull(viewModel.uiState.value.selection.session)
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selection.session)
    }

    @Test
    fun `a Select that finds nothing names the word and commits nothing`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(PlanStep.Select("없음", "n1"), PlanStep.Adjust(AdjustKind.Exposure, 0.1f, PlanTarget.New("n1"))),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        val message = viewModel.uiState.value.direct.message!!
        assertEquals(R.string.direct_not_found, message.res)
        assertEquals("없음", message.arg)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `a failure mid-run commits nothing, not even the steps before it`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, 0.5f),
                    PlanStep.Select("나무", "n1"),
                    PlanStep.Adjust(AdjustKind.Contrast, 0.1f, PlanTarget.New("n1")),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)
        segmentation.failNext(AppError.Unavailable)

        viewModel.applySheet()

        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse(viewModel.uiState.value.canUndo)
        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(R.string.direct_failed, viewModel.uiState.value.direct.message?.res)
    }

    // ---- T53: the shape a mixed plan leaves behind -------------------------

    @Test
    fun `a select, erase and global adjust land in that order with the adjust unmasked`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("bus", "n1"),
                    PlanStep.Erase("n1"),
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.previewAndApply()

        val operations = viewModel.uiState.value.document!!.operations
        // Select, then the erase's own margin mask + result (T50), then the adjustment — and the
        // adjustment is last, which is what makes it visible after T49.
        assertEquals(
            listOf("Mask", "Mask", "GenerativeErase", "Adjust"),
            operations.map { it::class.simpleName },
        )
        val adjust = operations.filterIsInstance<Operation.Adjust>().single()
        assertNull("a whole-photo adjustment must not be scoped to the erased hole", adjust.maskId)
    }

    /**
     * T70: a plan's `Fill` and a tap on 채우기 send the same frame and commit to the same place,
     * so the two paths cannot drift. Here the adjustment is made *first*, by an earlier step of
     * the same plan, which is the case the run's one `preview` snapshot could never have got right.
     */
    @Test
    fun `a plan's fill is shown the plain frame and lands under an earlier adjust`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f),
                    PlanStep.Select("chair", "n1"),
                    PlanStep.Fill("a red umbrella", "n1"),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.previewAndApply()

        assertEquals(PLAIN, filler.lastImage!!.getPixel(0, 0))
        val operations = viewModel.uiState.value.document!!.operations
        val fill = operations.indexOfFirst { it is Operation.GenerativeFill }
        val adjust = operations.indexOfFirst { it is Operation.Adjust }
        assertTrue(
            "the fill must run before the adjustment, was $operations",
            fill in 0 until adjust,
        )
        assertTrue(viewModel.uiState.value.document!!.referencesResolve())
    }

    @Test
    fun `one undo takes a whole applied plan back`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("bus", "n1"),
                    PlanStep.Erase("n1"),
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)
        viewModel.previewAndApply()

        viewModel.undo()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        viewModel.redo()
        assertEquals(4, viewModel.uiState.value.document!!.operations.size)
    }

    @Test
    fun `the erase in a plan is told what the Select was looking for`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Select("bus", "n1"), PlanStep.Erase("n1"))))
        viewModel.direct.submit(REQUEST)

        viewModel.previewAndApply()

        assertEquals("bus", eraser.lastHint)
    }

    // ---- fixtures ---------------------------------------------------------

    private suspend fun opened(): EditorViewModel = viewModel().also { it.onToolClick(Tool.Direct) }

    private fun viewModel() = EditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        repository = repository,
        renderer = renderer,
        ai = EditorAi(
            segmentation,
            eraser,
            filler,
            FakeOutpaintProvider(),
            planner,
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

    private fun assertNotNull(value: Any?) =
        assertTrue("expected a value, got null", value != null)

    private companion object {
        const val PROJECT_ID = "p"
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        const val PICKED = "사진 전체를 조금 더 밝게 해줘"
        val PLAIN = DirectFakeRenderer.PLAIN
    }
}
