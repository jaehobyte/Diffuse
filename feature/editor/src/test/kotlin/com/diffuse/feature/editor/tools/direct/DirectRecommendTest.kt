package com.diffuse.feature.editor.tools.direct

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

/** specs/vibe_edit.md §14: 보정 추천, the shared planner, its snapshot and its lifetime. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectRecommendTest {

    private lateinit var harness: DirectHarness
    private val planner get() = harness.planner
    private val segmentation get() = harness.segmentation
    private val renderer get() = harness.renderer
    private val geminiSettings get() = harness.geminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = DirectHarness()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- §14, 보정 추천 and the shared planner ----------------------------

    @Test
    fun `opening the sheet sends nothing`() = runTest {
        val viewModel = opened()

        val state = viewModel.uiState.value.direct
        assertEquals(0, planner.planCount)
        assertEquals(ProposalPhase.Idle, state.phase)
        assertTrue(state.proposals.frameReady)
        assertTrue(state.showExamples)
        assertTrue(state.canRecommend)
    }

    @Test
    fun `an empty bar asks for a recommendation of the photo alone, and nothing changes`() = runTest {
        val viewModel = opened()

        viewModel.direct.recommend()

        assertEquals(1, planner.planCount)
        val request = planner.lastRequest!!
        assertEquals("" to PlanMode.Recommend, request.text to request.mode)
        // §15: an untouched photo — no values, no selections, no operations — at the frame's size.
        val context = request.context
        assertEquals(emptyList<Any>(), context.adjustments + context.targets + context.operations)
        assertEquals(DirectFakeRenderer.SIZE, request.context.imageWidth)
        // §15: the catalog's own values for every style, and Topaz as configured — no key text.
        assertEquals(28, request.context.styles.size)
        assertFalse(request.context.topaz!!.hasAlpha)
        val state = viewModel.uiState.value
        assertEquals(ProposalPhase.Ready, state.direct.phase)
        assertTrue(state.direct.canApply)
        assertFalse("a recommendation is inline, not the overlay", state.direct.planning)
        assertEquals(0, segmentation.openCount)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
    }

    @Test
    fun `typed text is recommended on as it is, and typing keeps the entry`() = runTest {
        val viewModel = opened()
        viewModel.direct.setRequest("얼굴만 밝게")
        assertTrue(viewModel.uiState.value.direct.canRecommend)
        assertFalse(viewModel.uiState.value.direct.showExamples)

        viewModel.direct.recommend()

        assertEquals("얼굴만 밝게", planner.lastRequest?.text)
        assertEquals(PlanMode.Recommend, planner.lastRequest?.mode)
        assertEquals("얼굴만 밝게", viewModel.uiState.value.direct.request)
    }

    @Test
    fun `repeated taps while loading are one call`() = runTest {
        val viewModel = opened()
        planner.hold(ignoreCancellation = false)

        viewModel.direct.recommend()
        viewModel.direct.recommend()
        assertTrue(viewModel.uiState.value.direct.recommending)
        planner.release()

        assertEquals(1, planner.planCount)
        assertEquals(ProposalPhase.Ready, viewModel.uiState.value.direct.phase)
    }

    @Test
    fun `the request carries the frame on screen and its current settings as final values`() = runTest {
        val viewModel = opened()
        viewModel.onAdjust(AdjustKind.Exposure, 0.4f)

        viewModel.direct.recommend()

        assertEquals(ADJUSTED, planner.lastImage!!.getPixel(0, 0))
        assertEquals(
            listOf(AdjustKind.Exposure to 0.4f),
            planner.lastRequest?.context?.adjustments?.map { it.kind to it.value },
        )
        assertEquals(
            listOf(null),
            planner.lastRequest?.context?.adjustments?.map { it.targetRef },
        )
    }

    /** §14: "더 밝게" on an already brightened photo must not darken it — the value is final. */
    @Test
    fun `brighter on a raised exposure lands as the higher final setting`() = runTest {
        val viewModel = opened()
        viewModel.onAdjust(AdjustKind.Exposure, 0.4f)
        viewModel.onAdjustFinished()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.55f))))

        viewModel.direct.submit("더 밝게")
        viewModel.previewAndApply()

        val document = viewModel.uiState.value.document!!
        assertEquals(0.55f, document.adjustValue(AdjustKind.Exposure), 1e-6f)
        assertEquals(1, document.operations.filterIsInstance<Operation.Adjust>().size)
        viewModel.undo()
        assertEquals(0.4f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), 1e-6f)
    }

    @Test
    fun `미리보기 and 적용 run the reviewed plan and ask nothing again`() = runTest {
        val viewModel = opened()
        viewModel.direct.recommend()

        viewModel.previewAndApply()

        assertEquals(1, planner.planCount)
        assertEquals(2, viewModel.uiState.value.document!!.operations.size)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `no_change, needs_clarification and unsupported leave 적용 off and edit nothing`() = runTest {
        val viewModel = opened()
        listOf(
            PlanOutcome.NoChange to ProposalPhase.NoChange,
            PlanOutcome.NeedsClarification to ProposalPhase.NeedsClarification,
            PlanOutcome.Unsupported to ProposalPhase.Unsupported,
        ).forEach { (outcome, phase) ->
            planner.next(PlanProposal(outcome, reason = "이유"))

            viewModel.direct.recommend(again = true)
            viewModel.applySheet()

            val state = viewModel.uiState.value
            assertEquals(phase, state.direct.phase)
            assertFalse(state.direct.canApply)
            assertNull(state.direct.message)
            assertEquals(Tool.Direct, state.selectedTool)
            assertEquals(emptyList<Operation>(), state.document?.operations)
        }
    }

    @Test
    fun `a refused proposal is the hint, not a snackbar`() = runTest {
        val viewModel = opened()
        planner.failNext(AppError.Invalid("unplannable:a step names no goal"))

        viewModel.direct.recommend()

        val state = viewModel.uiState.value.direct
        assertTrue(state.notUnderstood)
        assertNull(state.message)
        assertFalse(state.canApply)
    }

    @Test
    fun `a failed recommendation says so once and only an explicit retry asks again`() = runTest {
        val viewModel = opened()
        viewModel.direct.setRequest("하늘")
        planner.failNext(AppError.Unavailable)

        viewModel.direct.recommend()

        val state = viewModel.uiState.value.direct
        assertEquals(ProposalPhase.Failed, state.phase)
        assertEquals(R.string.direct_recommend_failed, state.message?.res)
        assertEquals("하늘", state.request)
        assertEquals("no automatic retry", 0, planner.planCount)
        viewModel.direct.recommend()
        assertEquals(1, planner.planCount)
        assertEquals(ProposalPhase.Ready, viewModel.uiState.value.direct.phase)
    }

    @Test
    fun `an identical snapshot is not asked twice, and 다시 추천 asks once more`() = runTest {
        val viewModel = opened()
        viewModel.direct.recommend()

        viewModel.direct.recommend()
        assertEquals(1, planner.planCount)

        viewModel.direct.recommend(again = true)
        assertEquals(2, planner.planCount)
    }

    @Test
    fun `an answer for an empty bar is never reused for typed text`() = runTest {
        val viewModel = opened()
        viewModel.direct.recommend()

        viewModel.direct.setRequest("버스만 강조해줘")
        assertNull(viewModel.uiState.value.direct.proposal)
        viewModel.direct.recommend()

        assertEquals(2, planner.planCount)
        assertEquals("버스만 강조해줘", planner.lastRequest?.text)
    }

    @Test
    fun `a late answer after the text changed is dropped`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.recommend()

        viewModel.direct.setRequest("하늘을")
        planner.release()

        val state = viewModel.uiState.value.direct
        assertEquals("하늘을", state.request)
        assertEquals(ProposalPhase.Idle, state.phase)
        assertNull(state.proposal)
    }

    @Test
    fun `a late answer for an older document is dropped`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.recommend()

        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        planner.release()

        assertNull(viewModel.uiState.value.direct.proposal)
        assertEquals(ProposalPhase.Idle, viewModel.uiState.value.direct.phase)
    }

    @Test
    fun `undo and redo drop a proposal asked of another document`() = runTest {
        val viewModel = opened()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.onAdjustFinished()
        viewModel.direct.recommend()

        viewModel.undo()
        assertNull(viewModel.uiState.value.direct.proposal)
        viewModel.redo()
        assertNull(viewModel.uiState.value.direct.proposal)
        assertEquals("never re-asked on its own", 1, planner.planCount)
    }

    @Test
    fun `a Gemini settings change drops the proposal and the request in flight`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.recommend()

        geminiSettings.update("another-key")
        planner.release()

        assertEquals(ProposalPhase.Idle, viewModel.uiState.value.direct.phase)
        viewModel.direct.recommend()
        assertEquals(2, planner.planCount)
    }

    @Test
    fun `a ready proposal goes when the settings change before 적용`() = runTest {
        val viewModel = opened()
        viewModel.direct.recommend()

        geminiSettings.update("another-key")
        viewModel.applySheet()

        assertFalse(viewModel.uiState.value.direct.canApply)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `closing the sheet drops a late answer, and reopening asks anew`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.recommend()

        viewModel.cancelSheet()
        planner.release()

        assertNull(viewModel.uiState.value.direct.proposal)
        viewModel.onToolClick(Tool.Direct)
        assertEquals(ProposalPhase.Idle, viewModel.uiState.value.direct.phase)
        viewModel.direct.recommend()
        assertEquals(2, planner.planCount)
    }

    @Test
    fun `the inline 취소 keeps the text and the document, and ignores a late answer`() = runTest {
        val viewModel = opened()
        viewModel.direct.setRequest("꽃을 화사하게")
        planner.hold()
        viewModel.direct.recommend()

        viewModel.direct.cancelRecommendation()
        planner.release()

        val state = viewModel.uiState.value
        assertEquals(ProposalPhase.Cancelled, state.direct.phase)
        assertNull(state.direct.proposal)
        assertEquals("꽃을 화사하게", state.direct.request)
        assertEquals(Tool.Direct, state.selectedTool)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
    }

    @Test
    fun `sending a 지시 drops the recommendation still in flight`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.recommend()
        planner.release()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Contrast, 0.2f))))

        viewModel.direct.submit(REQUEST)

        val state = viewModel.uiState.value.direct
        assertEquals(2, planner.planCount)
        assertEquals(PlanMode.Direct, planner.lastRequest?.mode)
        assertEquals(PlanMode.Direct, state.proposals.mode)
        assertEquals(listOf(PlanStep.Adjust(AdjustKind.Contrast, 0.2f)), state.plan?.steps)
    }

    @Test
    fun `render A still on screen for document B sends nothing until B's frame exists`() = runTest {
        val viewModel = opened()
        renderer.gate = CompletableDeferred()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)

        viewModel.direct.recommend()
        assertFalse(viewModel.uiState.value.direct.proposals.frameReady)
        viewModel.direct.submit(REQUEST)
        assertEquals(0, planner.planCount)
        assertTrue("the 지시 waits behind the overlay", viewModel.uiState.value.direct.planning)

        renderer.gate!!.complete(Unit)

        assertEquals(1, planner.planCount)
        assertEquals(ADJUSTED, planner.lastImage!!.getPixel(0, 0))
        assertEquals(PlanMode.Direct, planner.lastRequest?.mode)
    }

    @Test
    fun `a failed render sends nothing`() = runTest {
        val viewModel = opened()
        renderer.fail = true

        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.direct.recommend()

        assertFalse(viewModel.uiState.value.direct.proposals.frameReady)
        assertEquals(0, planner.planCount)
    }

    @Test
    fun `picking an example only fills the bar`() = runTest {
        val viewModel = opened()

        viewModel.direct.pickSuggestion(PICKED)

        val state = viewModel.uiState.value
        assertEquals(PICKED, state.direct.request)
        assertEquals(RequestSource.Suggestion, state.direct.requestSource)
        assertTrue("an unedited example can still be swapped", state.direct.showExamples)
        assertEquals(0, planner.planCount)
        assertNull(state.direct.plan)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
    }

    @Test
    fun `changing the sentence after a plan makes it unappliable`() = runTest {
        val viewModel = opened()
        viewModel.direct.pickSuggestion(PICKED)
        viewModel.direct.submit(PICKED)
        assertTrue(viewModel.uiState.value.direct.canApply)

        viewModel.direct.setRequest("사진 전체를 많이 밝게 해줘")

        assertNull(viewModel.uiState.value.direct.plan)
        viewModel.applySheet()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `a late plan for the sentence before a swap is dropped`() = runTest {
        val viewModel = opened()
        planner.hold()
        viewModel.direct.submit(REQUEST)

        viewModel.direct.pickSuggestion(PICKED)
        planner.release()

        val state = viewModel.uiState.value.direct
        assertEquals(PICKED, state.request)
        assertNull(state.plan)
        assertFalse(state.planning)
    }

    @Test
    fun `hidden examples stay hidden for the session and 보정 추천 stays reachable`() = runTest {
        val viewModel = opened()

        viewModel.direct.hideExamples()

        assertFalse(viewModel.uiState.value.direct.showExamples)
        assertTrue(viewModel.uiState.value.direct.canRecommend)
        viewModel.cancelSheet()
        viewModel.onToolClick(Tool.Direct)
        assertTrue(viewModel.uiState.value.direct.showExamples)
    }

    @Test
    fun `a new ViewModel restores no proposal and runs nothing`() = runTest {
        val first = opened()
        first.direct.recommend()

        val second = opened()

        assertNull(second.uiState.value.direct.proposal)
        assertEquals(1, planner.planCount)
        assertEquals(emptyList<Operation>(), second.uiState.value.document?.operations)
    }

    private fun opened(): EditorViewModel = harness.opened()

    private companion object {
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        const val PICKED = "사진 전체를 조금 더 밝게 해줘"
        val ADJUSTED = DirectFakeRenderer.ADJUSTED
    }
}
