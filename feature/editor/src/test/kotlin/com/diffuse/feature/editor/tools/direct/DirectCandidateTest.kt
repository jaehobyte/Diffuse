package com.diffuse.feature.editor.tools.direct

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.enhance.TopazGroup
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * specs/vibe_edit.md §15–§18: a ready plan is a preview, not a result. The candidate is built off
 * the document and its history, every new selection is confirmed on the overlay, and only a
 * reviewed candidate is committed — once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectCandidateTest {

    private lateinit var harness: DirectHarness
    private val planner get() = harness.planner
    private val segmentation get() = harness.segmentation
    private val repository get() = harness.repository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = DirectHarness()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- isolation --------------------------------------------------------

    @Test
    fun `a candidate never reaches the document, its history or the saved project`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)

        reachReview(viewModel)

        val candidate = viewModel.uiState.value.direct.candidate!!
        assertEquals(2, candidate.document!!.operations.size)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse(viewModel.uiState.value.canUndo)
        // Leaving autosaves the document — the input, never the candidate.
        viewModel.onLeave()
        assertEquals(emptyList<Operation>(), repository.document.operations)
    }

    /** §18: a process restart reloads the saved project; a candidate is not restored. */
    @Test
    fun `re-entering after a preview opens the original`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)

        val reopened = harness.opened()

        assertEquals(emptyList<Operation>(), reopened.uiState.value.document!!.operations)
        assertNull(reopened.uiState.value.direct.candidate)
    }

    @Test
    fun `되돌리기 drops the candidate and keeps the proposal, its files go with the session`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)
        val mask = viewModel.uiState.value.direct.candidate!!.document!!
            .operations.filterIsInstance<Operation.Mask>().single().maskRef.path

        viewModel.direct.discardCandidate()

        assertNull(viewModel.uiState.value.direct.candidate)
        // §19: the confirmed selection is the session's, kept so another 미리보기 reuses it.
        assertEquals(emptyList<String>(), repository.discarded)
        assertEquals(ProposalPhase.Ready, viewModel.uiState.value.direct.phase)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)

        viewModel.cancelSheet()

        assertEquals(listOf(mask), repository.discarded)
    }

    /** §19: only an existing selection of unknown meaning waits on the overlay; a no stops there. */
    @Test
    fun `a no on the overlay stops before anything uses the selection`() = runTest {
        repository.document = repository.document.withMask(ImageRef("/p/mask_manual.png"), id = "manual")
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.Existing("t1", "manual")))))
        viewModel.direct.submit("선택한 부분 밝게")

        viewModel.applySheet()
        val check = viewModel.uiState.value.direct.candidate!!.check!!
        assertNull(check.phrase)
        viewModel.direct.discardCandidate()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(R.string.direct_selection_rejected, viewModel.uiState.value.direct.message?.res)
        assertEquals(1, viewModel.uiState.value.document!!.operations.size)
    }

    @Test
    fun `an undo under the review drops the candidate as stale`() = runTest {
        val viewModel = harness.viewModel()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.Direct)
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)

        viewModel.undo()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertNull(viewModel.uiState.value.direct.plan)
        assertEquals(R.string.direct_stale, viewModel.uiState.value.direct.message?.res)
        assertTrue(repository.discarded.isNotEmpty())
    }

    @Test
    fun `a second 적용 tap commits nothing more`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)

        viewModel.direct.adopt()
        viewModel.direct.adopt()

        assertEquals(2, viewModel.uiState.value.document!!.operations.size)
        viewModel.undo()
        assertFalse(viewModel.uiState.value.canUndo)
        // Applied files are the document's now: nothing was deleted.
        assertTrue(repository.discarded.isEmpty())
    }

    @Test
    fun `closing the sheet while a selection waits commits nothing`() = runTest {
        repository.document = repository.document.withMask(ImageRef("/p/mask_manual.png"), id = "manual")
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.Existing("t1", "manual")))))
        viewModel.direct.submit("선택한 부분 밝게")
        viewModel.applySheet()
        assertEquals(CandidatePhase.Confirming, viewModel.uiState.value.direct.candidate?.phase)

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(1, viewModel.uiState.value.document!!.operations.size)
    }

    // ---- §15 targets ------------------------------------------------------

    /**
     * §15's "얼굴을 밝게" → "조금만 더": the applied face selection is in the next snapshot as a
     * target found for "face" (§19: found, not confirmed by the user), and a follow-up on it
     * updates the same `Adjust` in place — no new mask.
     */
    @Test
    fun `a follow-up on a found face updates its own setting in place`() = runTest {
        val viewModel = harness.opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("face", "n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.New("n1")),
                ),
            ),
        )
        viewModel.direct.submit("얼굴을 밝게")
        viewModel.previewAndApply()
        val first = viewModel.uiState.value.document!!
        val faceId = first.operations.filterIsInstance<Operation.Mask>().single().id
        val adjustId = first.operations.filterIsInstance<Operation.Adjust>().single().id

        viewModel.onToolClick(Tool.Direct)
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.3f, PlanTarget.Existing("t1", faceId)))))
        viewModel.direct.submit("조금만 더")

        val target = planner.lastRequest!!.context.targets.single()
        assertEquals("t1" to "face", target.ref to target.description)
        assertEquals(TargetSource.Resolved, target.source)
        // Found this session, so its meaning is known: no overlay question.
        viewModel.applySheet()
        assertEquals(CandidatePhase.Review, viewModel.uiState.value.direct.candidate?.phase)
        viewModel.applySheet()

        val second = viewModel.uiState.value.document!!
        assertEquals(listOf(faceId), second.operations.filterIsInstance<Operation.Mask>().map { it.id })
        val adjust = second.operations.filterIsInstance<Operation.Adjust>().single()
        assertEquals(adjustId to 0.3f, adjust.id to adjust.value)
        assertEquals(faceId, adjust.maskId)
    }

    /** §15: a selection made by hand has no description, so its meaning is confirmed on the overlay. */
    @Test
    fun `an unknown existing selection is shown for confirmation before it is used`() = runTest {
        repository.document = repository.document.withMask(ImageRef("/p/mask_manual.png"), id = "manual")
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.Existing("t1", "manual")))))
        viewModel.direct.submit("선택한 부분 밝게")

        val target = planner.lastRequest!!.context.targets.single()
        assertNull(target.description)
        assertEquals(TargetSource.Unknown, target.source)
        viewModel.applySheet()

        val check = viewModel.uiState.value.direct.candidate!!.check!!
        assertNull("an existing selection has no phrase", check.phrase)
        assertEquals("manual", check.maskId)
    }

    // ---- §18 what the segmenter answered -----------------------------------

    @Test
    fun `several matches for one thing stop with the word, and are never unioned`() = runTest {
        segmentation.textInstances = 2
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(DirectMessage(R.string.direct_ambiguous, "나무"), viewModel.uiState.value.direct.message)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `several matches for several things union into one confirmed selection`() = runTest {
        segmentation.textInstances = 2
        val viewModel = harness.opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("balloons", "n1", multiple = true),
                    PlanStep.Adjust(AdjustKind.Vibrance, 0.2f, PlanTarget.New("n1")),
                ),
            ),
        )
        viewModel.direct.submit("풍선들 색 살려줘")

        viewModel.previewAndApply()

        assertEquals(1, viewModel.uiState.value.document!!.operations.count { it is Operation.Mask })
    }

    @Test
    fun `an all-zero answer is nothing found, and nothing after it runs`() = runTest {
        segmentation.blankText = true
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertEquals(DirectMessage(R.string.direct_not_found, "나무"), viewModel.uiState.value.direct.message)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `an answer of the wrong size stops the run`() = runTest {
        segmentation.textMaskSize = 7 to 9
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(R.string.direct_failed, viewModel.uiState.value.direct.message?.res)
    }

    // ---- §18 강도 줄이기 ---------------------------------------------------

    @Test
    fun `강도 줄이기 halves toward this input, not toward zero`() = runTest {
        val viewModel = harness.viewModel()
        viewModel.onAdjust(AdjustKind.Exposure, 0.4f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.Direct)
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.6f))))
        viewModel.direct.submit("더 밝게")
        reachReview(viewModel)

        viewModel.direct.reduce()
        assertEquals(0.5f, candidateValue(viewModel), 1e-6f)
        viewModel.direct.reduce()
        assertEquals(0.45f, candidateValue(viewModel), 1e-6f)
        viewModel.applySheet()

        assertEquals(0.45f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), 1e-6f)
        viewModel.undo()
        assertEquals(0.4f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), 1e-6f)
    }

    @Test
    fun `a new selection's setting halves toward zero, its starting value`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)
        val calls = segmentation.openCount

        viewModel.direct.reduce()

        val adjust = viewModel.uiState.value.direct.candidate!!.document!!
            .operations.filterIsInstance<Operation.Adjust>().single()
        assertEquals(0.15f, adjust.value, 1e-6f)
        assertEquals("halving re-runs no segmentation", calls, segmentation.openCount)
    }

    @Test
    fun `halved down to the input there is nothing to apply`() = runTest {
        val viewModel = harness.viewModel()
        viewModel.onAdjust(AdjustKind.Exposure, 0.4f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.Direct)
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.43f))))
        viewModel.direct.submit("아주 조금 밝게")
        reachReview(viewModel)

        viewModel.direct.reduce()
        assertEquals(0.415f, candidateValue(viewModel), 1e-6f)
        viewModel.direct.reduce()
        assertEquals(0.4075f, candidateValue(viewModel), 1e-6f)
        // Less than half a slider step from the input: the same setting.
        viewModel.direct.reduce()

        assertNull(viewModel.uiState.value.direct.candidate)
        assertEquals(R.string.direct_reduced_to_nothing, viewModel.uiState.value.direct.message?.res)
        assertEquals(0.4f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), 1e-6f)
    }

    @Test
    fun `a crop alone has nothing to halve`() = runTest {
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Crop(com.diffuse.core.ai.CropRatio.Square))))
        viewModel.direct.submit("정사각형으로")
        reachReview(viewModel)

        val candidate = viewModel.uiState.value.direct.candidate!!
        assertFalse(candidate.reducible)
        assertTrue(CandidateFinding.FrameCropped in candidate.findings)
    }

    // ---- §18 compare --------------------------------------------------------

    @Test
    fun `held, the canvas shows this input, released the candidate`() = runTest {
        val viewModel = harness.opened()
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)
        val before = viewModel.uiState.value.direct.candidate!!.before
        assertNotNull(before)
        assertEquals(DirectFakeRenderer.PLAIN, before!!.getPixel(0, 0))
        assertEquals(DirectFakeRenderer.ADJUSTED, viewModel.uiState.value.preview!!.asAndroidBitmap().getPixel(0, 0))

        viewModel.direct.setComparing(true)
        assertTrue(viewModel.uiState.value.direct.candidate!!.comparing)
        viewModel.direct.setComparing(false)
        assertFalse(viewModel.uiState.value.direct.candidate!!.comparing)
    }

    // ---- §16 Topaz -----------------------------------------------------------

    /**
     * §16, §19: one 실행 submits the plan's purpose and options once, in this sheet — the 화질 개선
     * sheet is not opened, and its 실행 is not needed.
     */
    @Test
    fun `a Topaz plan runs on its purpose and options in one tap, and the document stays`() = runTest {
        val viewModel = harness.opened()
        val options = TopazOptions(scale = 1, sharpenStrength = 0.4f)
        planner.next(EditPlan(listOf(PlanStep.Topaz(TopazPurpose.Sharpen, options))))
        viewModel.direct.submit("흐린 걸 선명하게 복원")
        assertNotNull(viewModel.uiState.value.direct.topazStep)

        viewModel.applySheet()

        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.topaz.group)
        assertEquals(1, harness.topaz.submitCount)
        val job = harness.topaz.lastJob!!
        assertEquals(TopazPurpose.Sharpen to options, job.purpose to job.options)
        assertNotNull(viewModel.uiState.value.direct.candidate!!.topaz)
        // The document is left alone: a result is a file or a new copy (D098).
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `options the purpose does not offer run as its defaults`() = runTest {
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Topaz(TopazPurpose.Lighting, TopazOptions(scale = 2)))))
        viewModel.direct.submit("조명 개선")

        viewModel.applySheet()

        assertEquals(TopazOptions.defaultsFor(TopazPurpose.Lighting), harness.topaz.lastJob!!.options)
        assertEquals(1, harness.topaz.submitCount)
    }

    @Test
    fun `a Topaz key change drops the proposal asked under the old one`() = runTest {
        val viewModel = harness.opened()
        planner.next(EditPlan(listOf(PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1)))))
        viewModel.direct.submit("노이즈 줄여줘")
        assertNotNull(viewModel.uiState.value.direct.plan)

        harness.topaz.setKey("another-key")

        assertNull(viewModel.uiState.value.direct.plan)
        viewModel.applySheet()
        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertEquals(0, harness.topaz.submitCount)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun reachReview(viewModel: EditorViewModel) {
        viewModel.applySheet()
        while (viewModel.uiState.value.direct.candidate?.phase == CandidatePhase.Confirming) viewModel.applySheet()
        assertEquals(CandidatePhase.Review, viewModel.uiState.value.direct.candidate?.phase)
    }

    private fun candidateValue(viewModel: EditorViewModel): Float =
        viewModel.uiState.value.direct.candidate!!.document!!.adjustValue(AdjustKind.Exposure)

    private companion object {
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
    }
}
