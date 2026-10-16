package com.diffuse.feature.editor.tools.direct

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.StyleId
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import kotlinx.coroutines.CompletableDeferred
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
 * specs/vibe_edit.md §19: the items of a plan are toggled before and after 미리보기. A toggle calls
 * nothing; a candidate is always the chosen steps run from the same input; what the session paid
 * for is reused for exactly the same input and never called again on its own; only the shown
 * choice is applied, once; the session's files go when it ends.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectChoiceTest {

    private lateinit var harness: DirectHarness
    private val planner get() = harness.planner
    private val segmentation get() = harness.segmentation
    private val eraser get() = harness.eraser
    private val repository get() = harness.repository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = DirectHarness()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- before 미리보기 ----------------------------------------------------

    @Test
    fun `a plan arrives with every item on, and toggling calls and changes nothing`() = runTest {
        val viewModel = planned(PORTRAIT)

        assertEquals(setOf("s2", "s3", "s5", "s6"), direct(viewModel).chosen)
        listOf("s2", "s5", "s6", "s2", "s5", "s6").forEach(viewModel.direct::toggle)

        assertEquals(setOf("s2", "s3", "s5", "s6"), direct(viewModel).chosen)
        assertEquals(0, segmentation.openCount)
        assertEquals(0, segmentation.textCount)
        assertEquals(0, eraser.eraseCount)
        assertEquals(1, planner.planCount)
        assertNull(direct(viewModel).candidate)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `with every item off nothing can be previewed and no history is made`() = runTest {
        val viewModel = planned(PORTRAIT)
        listOf("s2", "s3", "s5", "s6").forEach(viewModel.direct::toggle)

        assertTrue(direct(viewModel).nothingChosen)
        assertFalse(direct(viewModel).canApply)
        viewModel.applySheet()

        assertNull(direct(viewModel).candidate)
        assertFalse(viewModel.uiState.value.canUndo)
        assertEquals(0, segmentation.textCount)
    }

    // ---- selections are dependencies ----------------------------------------

    @Test
    fun `one of two items on the face keeps its selection and drops only that effect`() = runTest {
        val viewModel = planned(PORTRAIT)
        viewModel.direct.toggle("s3")

        reachReview(viewModel)

        assertEquals("face and sign, once each", 2, segmentation.textCount)
        val candidate = candidate(viewModel)
        val face = candidate.operations.filterIsInstance<Operation.Mask>().first().id
        assertEquals(0.2f, candidate.adjustValue(AdjustKind.Exposure, face), TOLERANCE)
        assertEquals(0f, candidate.adjustValue(AdjustKind.Contrast, face), TOLERANCE)
    }

    @Test
    fun `both items on the face off - no segmentation for it, the sign keeps its own`() = runTest {
        val viewModel = planned(PORTRAIT)
        viewModel.direct.toggle("s2")
        viewModel.direct.toggle("s3")

        reachReview(viewModel)

        assertEquals("only the sign is segmented", 1, segmentation.textCount)
        val candidate = candidate(viewModel)
        assertTrue(candidate.operations.none { it is Operation.Adjust && it.maskId != null })
        assertEquals(1, candidate.operations.count { it is Operation.GenerativeErase })
    }

    @Test
    fun `an existing selection is reused in place, never segmented again`() = runTest {
        val seeded = repository.document.withMask(ImageRef("/p/mask_old.png"), "old")
        repository.document = seeded
        val viewModel = planned(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, 0.3f, PlanTarget.Existing("t1", "old")),
                    PlanStep.Select("sky", "n1"),
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f, PlanTarget.New("n1")),
                ),
            ),
        )
        viewModel.direct.toggle("s3")

        reachReview(viewModel)

        assertEquals(0, segmentation.textCount)
        val candidate = candidate(viewModel)
        assertEquals(
            seeded.operations.filterIsInstance<Operation.Mask>(),
            candidate.operations.filterIsInstance<Operation.Mask>(),
        )
        assertEquals(0.3f, candidate.adjustValue(AdjustKind.Exposure, "old"), TOLERANCE)
    }

    // ---- a choice equals running only that choice ---------------------------

    /**
     * Style then a further adjust, an explicit 0 resetting an existing value, an erase and a crop:
     * whatever is turned off, the candidate is the same document a plan of only the rest builds
     * from the same input — not the full result with operations taken out of it.
     */
    @Test
    fun `a partial choice is the plan of only those steps, run from the same input`() = runTest {
        repository.document = repository.document.withAdjust(AdjustKind.Exposure, EXISTING)
        val viewModel = planned(MIXED)
        reachReview(viewModel)
        assertEquals(1, eraser.eraseCount)

        val choices = listOf(
            setOf("s1"),
            setOf("s3"),
            setOf("s1", "s5"),
            setOf("s2", "s6"),
            setOf("s3", "s5", "s6"),
        )
        choices.forEach { off ->
            MIXED_IDS.filterNot { it in direct(viewModel).chosen || it in off }.forEach(viewModel.direct::toggle)
            off.filter { it in direct(viewModel).chosen }.forEach(viewModel.direct::toggle)
            assertEquals(off.toString(), CandidatePhase.Review, direct(viewModel).candidate?.phase)

            val expected = fresh(MIXED.without(off), seed = repository.document)
            assertEquals(off.toString(), shape(expected), shape(candidate(viewModel)))
        }
        assertEquals("the erase is reused for the same input", 1, eraser.eraseCount)
    }

    @Test
    fun `an explicit 0 turned off leaves the existing value where it was`() = runTest {
        repository.document = repository.document.withAdjust(AdjustKind.Exposure, EXISTING)
        val viewModel = planned(
            EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0f), PlanStep.Adjust(AdjustKind.Saturation, 0.2f))),
        )
        reachReview(viewModel)
        assertEquals(0f, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)

        viewModel.direct.toggle("s1")

        val first = candidate(viewModel).operations.first() as Operation.Adjust
        assertEquals(AdjustKind.Exposure, first.kind)
        assertEquals(EXISTING, first.value, TOLERANCE)
    }

    // ---- generated results ----------------------------------------------------

    @Test
    fun `an erase that is off is never called, nor its selection`() = runTest {
        val viewModel = planned(PORTRAIT)
        viewModel.direct.toggle("s5")

        reachReview(viewModel)

        assertEquals(0, eraser.eraseCount)
        assertEquals("the face only", 1, segmentation.textCount)
        assertTrue(candidate(viewModel).operations.none { it is Operation.GenerativeErase })
    }

    @Test
    fun `off then on again reuses the generated result for the same input`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)
        val erased = candidate(viewModel).operations.filterIsInstance<Operation.GenerativeErase>().single()

        viewModel.direct.toggle("s5")
        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertTrue(candidate(viewModel).operations.none { it is Operation.GenerativeErase })
        viewModel.direct.toggle("s5")

        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertEquals(erased, candidate(viewModel).operations.filterIsInstance<Operation.GenerativeErase>().single())
        assertEquals(1, eraser.eraseCount)
        assertEquals(2, segmentation.textCount)
    }

    @Test
    fun `an erase whose input changed waits for an explicit preview and only then calls`() = runTest {
        val viewModel = planned(TWO_ERASES)
        reachReview(viewModel)
        assertEquals(2, eraser.eraseCount)

        // The bin's erase is gone, so the sign's erase would be shown a different frame.
        viewModel.direct.toggle("s2")

        assertEquals(CandidatePhase.Stale, direct(viewModel).candidate?.phase)
        assertNull("the canvas shows the input, not an outdated result", direct(viewModel).candidate?.document)
        assertEquals(2, eraser.eraseCount)
        assertTrue(direct(viewModel).canApply)

        viewModel.applySheet()

        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertEquals(3, eraser.eraseCount)
        assertEquals("the sign's selection is reused", 2, segmentation.textCount)
        assertEquals(1, candidate(viewModel).operations.count { it is Operation.GenerativeErase })
    }

    @Test
    fun `comparing never calls anything again`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)

        viewModel.direct.setComparing(true)
        viewModel.direct.setComparing(false)

        assertEquals(1, eraser.eraseCount)
        assertEquals(2, segmentation.textCount)
    }

    // ---- strength --------------------------------------------------------------

    @Test
    fun `a new choice after 강도 줄이기 is rebuilt at the proposal's strength and says so`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        reachReview(viewModel)
        viewModel.direct.reduce()
        assertEquals(EXPOSURE / 2, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)

        viewModel.direct.toggle("s2")

        assertEquals(EXPOSURE, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(0f, candidate(viewModel).adjustValue(AdjustKind.Temperature), TOLERANCE)
        assertFalse(direct(viewModel).candidate!!.reduced)
        assertEquals(R.string.direct_choice_restrength, direct(viewModel).message?.res)
    }

    @Test
    fun `강도 줄이기 never brings back an item that is off`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        reachReview(viewModel)
        viewModel.direct.toggle("s2")

        viewModel.direct.reduce()

        assertEquals(0f, candidate(viewModel).adjustValue(AdjustKind.Temperature), TOLERANCE)
        assertEquals(EXPOSURE / 2, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)
    }

    // ---- 적용 ------------------------------------------------------------------

    @Test
    fun `적용 commits the choice on screen once, and undo, redo and the save agree`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        reachReview(viewModel)
        viewModel.direct.toggle("s2")
        val shown = candidate(viewModel)

        viewModel.applySheet()
        viewModel.applySheet()

        val committed = viewModel.uiState.value.document!!
        assertEquals(shown, committed)
        viewModel.undo()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse("one commit, one undo", viewModel.uiState.value.canUndo)
        viewModel.redo()
        assertEquals(committed, viewModel.uiState.value.document)
        viewModel.onLeave()
        assertEquals(committed.operations, repository.document.operations)
        assertEquals(committed.operations, harness.viewModel().uiState.value.document!!.operations)
    }

    @Test
    fun `a candidate built for another choice cannot be applied`() = runTest {
        val viewModel = planned(TWO_ERASES)
        reachReview(viewModel)
        viewModel.direct.toggle("s2")
        assertEquals(CandidatePhase.Stale, direct(viewModel).candidate?.phase)

        viewModel.direct.adopt()

        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse(viewModel.uiState.value.canUndo)
    }

    // ---- lifetime and files ---------------------------------------------------

    @Test
    fun `적용 deletes the session's files the applied document does not use, and only those`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)
        val erasePaths = candidate(viewModel).paths() - candidate(viewModel).withoutErase().paths()
        viewModel.direct.toggle("s5")

        viewModel.applySheet()

        val kept = viewModel.uiState.value.document!!.paths()
        assertTrue(kept.isNotEmpty())
        assertTrue(repository.discarded.none { it in kept })
        assertTrue(repository.discarded.containsAll(erasePaths))
    }

    @Test
    fun `closing the sheet deletes every file the session wrote`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)
        val written = candidate(viewModel).paths()

        viewModel.cancelSheet()

        assertEquals(written, repository.discarded.toSet())
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `a toggle while the candidate is being checked is ignored`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        val gate = CompletableDeferred<Unit>()
        harness.renderer.gate = gate
        viewModel.applySheet()
        assertEquals(CandidatePhase.Checking, direct(viewModel).candidate?.phase)

        viewModel.direct.toggle("s2")

        assertEquals(setOf("s1", "s2"), direct(viewModel).chosen)
        harness.renderer.gate = null
        gate.complete(Unit)
        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertEquals(setOf("s1", "s2"), direct(viewModel).candidate?.chosen)
    }

    @Test
    fun `a rebuild cancelled while it is checked never lands, and the choice stays editable`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        reachReview(viewModel)
        val gate = CompletableDeferred<Unit>()
        harness.renderer.gate = gate
        viewModel.direct.toggle("s2")
        assertEquals(CandidatePhase.Checking, direct(viewModel).candidate?.phase)

        viewModel.direct.cancelWork()
        harness.renderer.gate = null
        gate.complete(Unit)

        assertNull(direct(viewModel).candidate)
        assertEquals(R.string.direct_cancelled, direct(viewModel).message?.res)
        assertTrue(direct(viewModel).canToggle)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `a settings change drops the choice, the candidate and the session's files`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)
        val written = candidate(viewModel).paths()

        harness.geminiSettings.update("another-key")

        assertNull(direct(viewModel).candidate)
        assertNull(direct(viewModel).proposal)
        assertEquals(emptySet<String>(), direct(viewModel).chosen)
        assertEquals(written, repository.discarded.toSet())
    }

    // ---- D109: sliders on the adjustment items --------------------------------

    @Test
    fun `a slider moved before the run is what the run applies, and the row reads it`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        viewModel.direct.setValue("s1", 0.1f)
        viewModel.direct.setValue("s9", 0.1f)
        val exposure = direct(viewModel).items.first { it.id == "s1" }

        assertEquals(0.1f, direct(viewModel).valueOf(exposure), TOLERANCE)
        assertNull(direct(viewModel).candidate)
        reachReview(viewModel)

        assertEquals(0.1f, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)
        val temperature = candidate(viewModel).adjustValue(AdjustKind.Temperature)
        assertEquals("the other keeps its value", 0.2f, temperature, TOLERANCE)
    }

    /** D110: before the run the canvas follows the slider; the run itself starts from the photo without it. */
    @Test
    fun `a slider moved before the run is drawn at once, and the run is given the photo without it`() = runTest {
        val viewModel = planned(PORTRAIT)
        val document = viewModel.uiState.value.document!!
        assertEquals(PLAIN, viewModel.uiState.value.preview!!.asAndroidBitmap().getPixel(0, 0))

        viewModel.direct.setValue("s6", -0.3f)
        assertEquals(-0.3f, direct(viewModel).drawn(document).adjustValue(AdjustKind.Temperature), TOLERANCE)
        assertEquals("drawn as it moves", ADJUSTED, viewModel.uiState.value.preview!!.asAndroidBitmap().getPixel(0, 0))
        assertNull(direct(viewModel).candidate)
        assertEquals(emptyList<Operation>(), document.operations)

        viewModel.direct.setValue("s2", 0.6f)
        val masked = direct(viewModel).drawn(document).operations.filterIsInstance<Operation.Adjust>()
        assertEquals("the face is not selected yet, so its exposure waits for the run", 1, masked.size)
        viewModel.direct.toggle("s6")
        assertEquals("off, it is not drawn", document, direct(viewModel).drawn(document))
        assertEquals(PLAIN, viewModel.uiState.value.preview!!.asAndroidBitmap().getPixel(0, 0))
        viewModel.direct.toggle("s6")

        reachReview(viewModel)

        assertEquals("the eraser works on the photo, not the draft", PLAIN, eraser.lastImage!!.getPixel(0, 0))
        assertEquals(-0.3f, candidate(viewModel).adjustValue(AdjustKind.Temperature), TOLERANCE)
        val face = candidate(viewModel).operations.filterIsInstance<Operation.Mask>().first().id
        assertEquals(0.6f, candidate(viewModel).adjustValue(AdjustKind.Exposure, face), TOLERANCE)
    }

    @Test
    fun `a slider under the review moves the candidate at once, on its own mask, and calls nothing`() = runTest {
        val viewModel = planned(PORTRAIT)
        reachReview(viewModel)
        val face = candidate(viewModel).operations.filterIsInstance<Operation.Mask>().first().id

        viewModel.direct.setValue("s2", 0.6f)
        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertEquals(0.6f, candidate(viewModel).adjustValue(AdjustKind.Exposure, face), TOLERANCE)
        viewModel.direct.setValue("s6", -0.3f)
        assertEquals(-0.3f, candidate(viewModel).adjustValue(AdjustKind.Temperature), TOLERANCE)
        viewModel.direct.commitValue()

        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertTrue(direct(viewModel).candidate!!.reducible)
        val erases = candidate(viewModel).operations.count { it is Operation.GenerativeErase }
        assertEquals("the sign's erase is kept", 1, erases)
        assertEquals(2, segmentation.textCount)
        assertEquals(1, eraser.eraseCount)
        assertEquals(1, planner.planCount)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `적용 commits the slider's value, and the line follows 강도 줄이기`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        reachReview(viewModel)
        viewModel.direct.reduce()
        val exposure = direct(viewModel).items.first { it.id == "s1" }
        assertEquals(EXPOSURE / 2, direct(viewModel).valueOf(exposure), TOLERANCE)

        viewModel.direct.setValue("s1", 0.9f)
        viewModel.direct.commitValue()
        viewModel.applySheet()

        assertEquals(0.9f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(0.1f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Temperature), TOLERANCE)
        assertTrue(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `a slider is locked while the candidate is checked`() = runTest {
        val viewModel = planned(TWO_ADJUSTS)
        val gate = CompletableDeferred<Unit>()
        harness.renderer.gate = gate
        viewModel.applySheet()
        assertEquals(CandidatePhase.Checking, direct(viewModel).candidate?.phase)

        viewModel.direct.setValue("s1", 0.9f)

        assertTrue(direct(viewModel).values.isEmpty())
        harness.renderer.gate = null
        gate.complete(Unit)
        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
        assertEquals(EXPOSURE, candidate(viewModel).adjustValue(AdjustKind.Exposure), TOLERANCE)
    }

    // ---- fixtures ----------------------------------------------------------------

    private fun planned(plan: EditPlan): EditorViewModel {
        val viewModel = harness.opened()
        planner.next(plan)
        viewModel.direct.submit(REQUEST)
        return viewModel
    }

    private fun reachReview(viewModel: EditorViewModel) {
        viewModel.applySheet()
        while (direct(viewModel).candidate?.phase == CandidatePhase.Confirming) viewModel.applySheet()
        assertEquals(CandidatePhase.Review, direct(viewModel).candidate?.phase)
    }

    /** The candidate a fresh session builds for [plan] from [seed] — the reference for a choice. */
    private fun fresh(plan: EditPlan, seed: EditDocument): EditDocument {
        val other = DirectHarness()
        other.repository.document = seed
        val viewModel = other.opened()
        other.planner.next(plan)
        viewModel.direct.submit(REQUEST)
        reachReview(viewModel)
        return candidate(viewModel)
    }

    private fun direct(viewModel: EditorViewModel) = viewModel.uiState.value.direct

    private fun candidate(viewModel: EditorViewModel) = direct(viewModel).candidate!!.document!!

    private fun EditDocument.withoutErase() =
        copy(operations = operations.filterNot { it is Operation.GenerativeErase })

    /** Ids and file names are fresh per run; the shape is everything else, masks by position. */
    private fun shape(document: EditDocument): List<String> {
        val masks = document.operations.filterIsInstance<Operation.Mask>().map { it.id }
        fun mask(id: String?) = id?.let { "m${masks.indexOf(it)}" }
        return document.operations.map { op ->
            when (op) {
                is Operation.Adjust -> "adjust ${op.kind} ${"%.4f".format(op.value)} ${mask(op.maskId)}"
                is Operation.Mask -> "mask ${mask(op.id)}"
                is Operation.GenerativeErase -> "erase ${mask(op.maskId)}"
                is Operation.Crop -> "crop ${op.rect} ${op.angleDeg}"
                else -> op::class.simpleName.orEmpty()
            }
        } + "active ${mask(document.activeMaskId)}"
    }

    private fun EditPlan.without(off: Set<String>): EditPlan {
        val items = steps.mapIndexed { i, step -> "s${i + 1}" to step }
        val kept = items.filterNot { (id, step) -> id in off && step !is PlanStep.Select }.map { it.second }
        // A selection whose every consumer is off goes too, as it does in a choice.
        return EditPlan(
            kept.filter { step ->
                step !is PlanStep.Select || kept.any { it is PlanStep.Erase && it.target == step.label }
            },
        )
    }

    private companion object {
        const val REQUEST = "더 좋게"
        const val TOLERANCE = 1e-4f
        const val EXPOSURE = 0.4f
        const val EXISTING = 0.3f
        val PLAIN = DirectFakeRenderer.PLAIN
        val ADJUSTED = DirectFakeRenderer.ADJUSTED

        /** s1 face, s2 s3 on it; s4 sign, s5 its erase; s6 a whole-photo temperature. */
        val PORTRAIT = EditPlan(
            listOf(
                PlanStep.Select("face", "n1"),
                PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.New("n1")),
                PlanStep.Adjust(AdjustKind.Contrast, 0.1f, PlanTarget.New("n1")),
                PlanStep.Select("sign", "n2"),
                PlanStep.Erase("n2"),
                PlanStep.Adjust(AdjustKind.Temperature, 0.05f),
            ),
        )

        val TWO_ADJUSTS = EditPlan(
            listOf(PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE), PlanStep.Adjust(AdjustKind.Temperature, 0.2f)),
        )

        /** s1 bin, s2 its erase; s3 sign, s4 its erase. */
        val TWO_ERASES = EditPlan(
            listOf(
                PlanStep.Select("bin", "n1"),
                PlanStep.Erase("n1"),
                PlanStep.Select("sign", "n2"),
                PlanStep.Erase("n2"),
            ),
        )

        /**
         * s1 a style, s2 a further temperature after it, s3 an explicit 0 on the existing
         * exposure, s4 the sign, s5 its erase, s6 a crop.
         */
        val MIXED = EditPlan(
            listOf(
                PlanStep.Style(StyleId.FilmWarm, 60),
                PlanStep.Adjust(AdjustKind.Temperature, 0.5f),
                PlanStep.Adjust(AdjustKind.Exposure, 0f),
                PlanStep.Select("sign", "n1"),
                PlanStep.Erase("n1"),
                PlanStep.Crop(CropRatio.Portrait4x5),
            ),
        )
        val MIXED_IDS = listOf("s1", "s2", "s3", "s5", "s6")
    }
}
