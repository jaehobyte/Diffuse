package com.diffuse.feature.editor.tools.direct

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.TargetSource
import com.diffuse.core.ai.TopazBlock
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.enhance.TopazPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * specs/vibe_edit.md §19 (2026-10-14): one 선택한 보정 실행 runs every chosen step to the end — the
 * local steps into a candidate, the plan's own selections without a confirmation tap, the crop into
 * the candidate, and the Topaz step on the candidate's full render — and the whole result is
 * reviewed on this screen. Nothing reaches the document, its history, Browse or the nav graph until
 * the user keeps it: 적용, with Topaz as the photo itself, or 고해상도 저장 of the full file.
 *
 * Every "no paid call" is a count on the fakes; every "no new project" is the repository's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectWorkflowTest {

    private lateinit var harness: DirectHarness
    private val topaz get() = harness.topaz

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = DirectHarness()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        harness.topazRoot.deleteRecursively()
    }

    // ---- one run, to the end ---------------------------------------------

    @Test
    fun `denoise alone - one tap, one submit, the result on this canvas, nothing created or committed`() = runTest {
        val viewModel = planned(EditPlan(listOf(DENOISE)))

        viewModel.applySheet()

        val direct = direct(viewModel)
        assertEquals(CandidatePhase.Review, direct.candidate?.phase)
        assertTrue("the whole chosen result exists", direct.complete)
        assertEquals(1, topaz.submitCount)
        assertEquals(TopazPurpose.Denoise, topaz.lastJob!!.purpose)
        assertNear(FakeTopazProvider.RESULT_COLOR, direct.candidate!!.topaz!!.after.asAndroidBitmap().centre())
        // Nothing stored, nothing committed: the original is as it was.
        assertTrue(harness.repository.enhanced.isEmpty())
        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertFalse(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `exposure, crop and denoise run in one tap, and the upload is the full render of that candidate`() = runTest {
        val viewModel = planned(LOCAL_CROP_TOPAZ)

        viewModel.applySheet()

        val candidate = direct(viewModel).candidate!!
        assertEquals(CandidatePhase.Review, candidate.phase)
        assertNotNull(candidate.topaz)
        assertEquals(1, topaz.submitCount)
        // What Topaz was given: the reviewed candidate, crop and adjustment included, rendered in full.
        val rendered = harness.renderer.fullDocuments.last()
        assertEquals(candidate.document, rendered)
        assertTrue(rendered.operations.any { it is Operation.Crop })
        assertEquals(EXPOSURE, rendered.adjustValue(AdjustKind.Exposure), TOLERANCE)
        val upload = BitmapFactory.decodeByteArray(topaz.lastInputBytes, 0, topaz.lastInputBytes!!.size)
        assertNear(DirectFakeRenderer.ADJUSTED, upload.centre())
        // No 자르기 sheet, no 화질 개선 sheet, nothing committed on the way.
        assertEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.topaz.group)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `while it runs, the step and the Topaz stage are shown, and the canvas never shows half a result`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = planned(LOCAL_CROP_TOPAZ)

        viewModel.applySheet()

        val direct = direct(viewModel)
        assertEquals(CandidatePhase.Running, direct.candidate?.phase)
        assertEquals(TopazPhase.Uploading, direct.candidate?.topazPhase)
        assertEquals("the Topaz step is the last of three", 2 to 3, direct.candidate!!.step to direct.candidate!!.steps)
        assertTrue(direct.working)
        assertNull("the local half is not shown as the result", direct.shownDocument)
        assertFalse(direct.canToggle)
        topaz.gate!!.complete(Unit)
        assertNotNull(direct(viewModel).shownDocument)
    }

    @Test
    fun `a new selection and an erase complete without a confirmation tap, and the review names what was found`() =
        runTest {
            val viewModel = planned(
                EditPlan(listOf(PlanStep.Select("sign", "n1"), PlanStep.Erase("n1"))),
            )

            viewModel.applySheet()

            val candidate = direct(viewModel).candidate!!
            assertEquals(CandidatePhase.Review, candidate.phase)
            assertEquals(1, harness.eraser.eraseCount)
            assertEquals(listOf("sign"), candidate.resolved)
            viewModel.applySheet()
            val applied = viewModel.uiState.value.document!!.operations
            assertEquals(1, applied.filterIsInstance<Operation.GenerativeErase>().size)
        }

    @Test
    fun `a found selection is described to the next plan as found, never as confirmed by the user`() = runTest {
        val viewModel = planned(
            EditPlan(
                listOf(PlanStep.Select("face", "n1"), PlanStep.Adjust(AdjustKind.Exposure, 0.2f, PlanTarget.New("n1"))),
            ),
        )
        viewModel.applySheet()
        viewModel.applySheet()

        viewModel.onToolClick(Tool.Direct)
        harness.planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.3f))))
        viewModel.direct.submit("조금만 더")

        val target = harness.planner.lastRequest!!.context.targets.single()
        assertEquals("face", target.description)
        assertEquals(TargetSource.Resolved, target.source)
    }

    @Test
    fun `a target the segmenter cannot find stops with the word, and nothing after it runs`() = runTest {
        harness.segmentation.textInstances = 0
        val viewModel = planned(
            EditPlan(listOf(PlanStep.Select("sign", "n1"), PlanStep.Erase("n1"), DENOISE)),
        )

        viewModel.applySheet()

        assertNull(direct(viewModel).candidate)
        assertEquals(R.string.direct_not_found, direct(viewModel).message?.res)
        assertEquals(0, harness.eraser.eraseCount)
        assertEquals(0, topaz.submitCount)
    }

    // ---- choices before the run --------------------------------------------

    @Test
    fun `Topaz off applies the local edits once and submits nothing`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.direct.toggle("s2")

        viewModel.previewAndApply()

        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(EXPOSURE, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(0, topaz.submitCount)
        viewModel.undo()
        assertFalse("one entry", viewModel.uiState.value.canUndo)
    }

    @Test
    fun `with every item off nothing runs`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.direct.toggle("s1")
        viewModel.direct.toggle("s2")

        viewModel.applySheet()

        assertFalse(direct(viewModel).canApply)
        assertNull(direct(viewModel).candidate)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `without a key the Topaz item starts off, says why, and turning it off lets the rest run`() = runTest {
        harness = DirectHarness(topazKey = "")
        val viewModel = planned(LOCAL_AND_TOPAZ)

        assertEquals(TopazBlock.NoKey, direct(viewModel).blocks["s2"])
        assertEquals(setOf("s1"), direct(viewModel).chosen)
        viewModel.direct.toggle("s2")
        assertFalse("on, it blocks", direct(viewModel).canApply)
        viewModel.direct.toggle("s2")

        viewModel.previewAndApply()

        assertEquals(EXPOSURE, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `a transparent photo starts with the Topaz item off and blocked`() = runTest {
        harness.repository.document = harness.repository.document.copy(source = ImageRef("/p.png"))
        val viewModel = planned(LOCAL_AND_TOPAZ)

        assertTrue(direct(viewModel).blocks["s2"] is TopazBlock.Unavailable)
        assertFalse("s2" in direct(viewModel).chosen)
        viewModel.applySheet()
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `options the purpose does not offer run as its defaults`() = runTest {
        val viewModel = planned(EditPlan(listOf(PlanStep.Topaz(TopazPurpose.Lighting, TopazOptions(scale = 2)))))

        viewModel.applySheet()

        assertEquals(TopazOptions.defaultsFor(TopazPurpose.Lighting), topaz.lastJob!!.options)
        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `a local plan with no crop and no Topaz still applies once`() = runTest {
        val viewModel = planned(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE))))

        viewModel.previewAndApply()

        assertNull(viewModel.uiState.value.selectedTool)
        assertTrue(viewModel.uiState.value.document!!.operations.none { it is Operation.Crop })
        assertEquals(0, topaz.submitCount)
    }

    // ---- changing the choice after the run ----------------------------------

    @Test
    fun `Topaz off and on again for the same input reuses its result, and comparing submits nothing`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        val result = direct(viewModel).candidate!!.topaz

        viewModel.direct.toggle("s2")
        assertTrue("Topaz off shows the local candidate", direct(viewModel).complete)
        assertEquals(direct(viewModel).candidate!!.document, direct(viewModel).shownDocument)
        viewModel.direct.toggle("s2")
        viewModel.direct.setComparing(true)
        viewModel.direct.setComparing(false)

        assertEquals(result, direct(viewModel).candidate!!.topaz)
        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `a local change voids the Topaz result until 다시 실행, which submits once`() = runTest {
        val viewModel = planned(TWO_LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        assertEquals(1, topaz.submitCount)

        viewModel.direct.toggle("s2")

        val direct = direct(viewModel)
        assertTrue(direct.needsTopaz)
        assertNull("nothing stale is shown or kept", direct.shownDocument)
        assertTrue(direct.canApply)
        assertEquals(1, topaz.submitCount)
        viewModel.direct.save()
        viewModel.direct.adoptTopaz()
        assertTrue(harness.saves.isEmpty())
        assertTrue(harness.repository.enhanced.isEmpty())

        viewModel.applySheet()

        assertEquals(2, topaz.submitCount)
        assertEquals(0f, harness.renderer.fullDocuments.last().adjustValue(AdjustKind.Temperature), TOLERANCE)
        // Back to the first choice: that input's result is still this session's.
        viewModel.direct.toggle("s2")
        assertNotNull(direct(viewModel).candidate!!.topaz)
        assertEquals(2, topaz.submitCount)
    }

    @Test
    fun `강도 줄이기 changes the Topaz input, so its result waits for 다시 실행`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()

        viewModel.direct.reduce()

        assertTrue(direct(viewModel).needsTopaz)
        assertEquals(1, topaz.submitCount)
        viewModel.applySheet()
        assertEquals(2, topaz.submitCount)
        assertEquals(EXPOSURE / 2, harness.renderer.fullDocuments.last().adjustValue(AdjustKind.Exposure), TOLERANCE)
    }

    // ---- cancels, races and invalidation ------------------------------------

    @Test
    fun `cancelled while the Topaz input is prepared, nothing is submitted`() = runTest {
        harness.renderer.fullGate = CompletableDeferred()
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        assertEquals(TopazPhase.Preparing, direct(viewModel).candidate?.topazPhase)

        viewModel.direct.cancelWork()
        harness.renderer.fullGate!!.complete(Unit)

        assertNull(direct(viewModel).candidate)
        assertEquals(R.string.direct_cancelled, direct(viewModel).message?.res)
        assertEquals(0, topaz.submitCount)
        assertEquals("the proposal stays", ProposalPhase.Ready, direct(viewModel).phase)
    }

    @Test
    fun `a key change while the input is prepared drops the run, and nothing is submitted`() = runTest {
        harness.renderer.fullGate = CompletableDeferred()
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()

        topaz.setKey("another-key")
        harness.renderer.fullGate!!.complete(Unit)

        assertNull(direct(viewModel).candidate)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `another tool during the Topaz step cancels the job and keeps nothing`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()

        viewModel.onToolClick(Tool.Light)
        topaz.gate!!.complete(Unit)

        assertEquals(1, topaz.cancelledCount)
        assertNull(direct(viewModel).candidate)
        assertEquals(Tool.Light, viewModel.uiState.value.selectedTool)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
        assertTrue(harness.topazRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `run B after cancelling A carries only B`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        viewModel.direct.cancelWork()
        topaz.gate = null

        viewModel.applySheet()

        assertEquals(2, topaz.submitCount)
        assertEquals(1, topaz.cancelledCount)
        assertNotNull(direct(viewModel).candidate!!.topaz)
    }

    @Test
    fun `a provider that ignores its cancellation lands nothing, and its file is deleted`() = runTest {
        topaz.gate = CompletableDeferred()
        topaz.ignoreCancel = true
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        viewModel.direct.cancelWork()

        topaz.gate!!.complete(Unit)

        assertNull(direct(viewModel).candidate)
        val outputs = harness.topazRoot.walkTopDown().filter { it.isFile && it.name.startsWith("output_") }.toList()
        assertEquals(emptyList<java.io.File>(), outputs)
    }

    @Test
    fun `a second tap while it runs submits nothing more`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = planned(LOCAL_AND_TOPAZ)

        viewModel.applySheet()
        viewModel.applySheet()
        viewModel.direct.preview()
        topaz.gate!!.complete(Unit)

        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `an undo during the Topaz step drops the run as stale`() = runTest {
        val viewModel = harness.viewModel()
        viewModel.onAdjust(AdjustKind.Contrast, 0.2f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.Direct)
        harness.planner.next(LOCAL_AND_TOPAZ)
        viewModel.direct.submit("밝고 깨끗하게")
        topaz.gate = CompletableDeferred()
        viewModel.applySheet()

        viewModel.undo()
        topaz.gate!!.complete(Unit)

        assertNull(direct(viewModel).candidate)
        assertEquals(1, topaz.cancelledCount)
    }

    // ---- failures ----------------------------------------------------------

    @Test
    fun `a Topaz failure keeps the local candidate, and Topaz off applies it once`() = runTest {
        topaz.nextFailure = TopazFailure.Server
        val viewModel = planned(LOCAL_AND_TOPAZ)

        viewModel.applySheet()

        val candidate = direct(viewModel).candidate!!
        assertEquals(CandidatePhase.Review, candidate.phase)
        assertEquals(TopazFailure.Server, candidate.topazFailure)
        assertEquals(R.string.topaz_error_server, direct(viewModel).message?.res)
        assertNull("no partial result is shown", direct(viewModel).shownDocument)
        viewModel.direct.toggle("s2")
        viewModel.applySheet()

        assertEquals(EXPOSURE, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(1, topaz.submitCount)
        viewModel.undo()
        assertFalse(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `an unclear submit is said as such and never resubmitted by itself`() = runTest {
        topaz.nextFailure = TopazFailure.SubmitUnknown
        val viewModel = planned(LOCAL_AND_TOPAZ)

        viewModel.applySheet()

        assertEquals(R.string.topaz_error_submit_unknown, direct(viewModel).message?.res)
        assertEquals(1, topaz.submitCount)
        viewModel.direct.toggle("s2")
        viewModel.direct.toggle("s2")
        assertEquals(1, topaz.submitCount)
        // Only the user's own 다시 실행 asks again.
        viewModel.applySheet()
        assertEquals(2, topaz.submitCount)
        assertNotNull(direct(viewModel).candidate!!.topaz)
    }

    @Test
    fun `a failed 고해상도 저장 copies the same file again and never calls Topaz`() = runTest {
        harness.saveResult = Result.Failure(AppError.Io(java.io.IOException("full")))
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        val result = direct(viewModel).candidate!!.topaz!!

        viewModel.direct.save()
        assertEquals(R.string.topaz_save_failed, direct(viewModel).message?.res)
        harness.saveResult = Result.Success(Unit)
        viewModel.direct.save()

        assertEquals(listOf(result.file, result.file), harness.saves)
        assertTrue(direct(viewModel).candidate!!.saved)
        assertEquals(1, topaz.submitCount)
        // The file itself, at the result's own size: never re-encoded.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.file.path, bounds)
        assertEquals(result.width to result.height, bounds.outWidth to bounds.outHeight)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `a failed 적용 keeps the result for a retry that stores it once and submits nothing`() = runTest {
        harness.repository.failEnhance = true
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        val input = viewModel.uiState.value.document

        viewModel.applySheet()

        assertEquals(input, viewModel.uiState.value.document)
        assertEquals(R.string.topaz_adopt_failed, direct(viewModel).message?.res)
        assertNotNull(direct(viewModel).candidate!!.topaz)
        harness.repository.failEnhance = false
        viewModel.applySheet()
        assertEquals(enhancedRef(1), viewModel.uiState.value.document!!.source)
        assertEquals(1, topaz.submitCount)
    }

    // ---- keeping a Topaz result ----------------------------------------------

    @Test
    fun `적용 with a Topaz result stores it once as this project's photo, and one undo brings the input back`() =
        runTest {
            val viewModel = planned(LOCAL_AND_TOPAZ)
            viewModel.applySheet()
            val result = direct(viewModel).candidate!!.topaz!!
            val input = viewModel.uiState.value.document

            viewModel.applySheet()
            viewModel.applySheet()
            viewModel.direct.adoptTopaz()

            val stored = harness.repository.enhanced.single()
            assertEquals(result.width, stored.sourceWidthPx)
            val adopted = viewModel.uiState.value.document!!
            assertEquals(enhancedRef(1), adopted.source)
            assertEquals("the local edits are in the pixels", emptyList<Operation>(), adopted.operations)
            assertNull(adopted.activeMaskId)
            assertNull(viewModel.uiState.value.selectedTool)
            assertNull(direct(viewModel).candidate)
            assertEquals(1, topaz.submitCount)
            // The session's files go with it; the photo is the project's own file now.
            assertTrue(harness.topazRoot.listFiles().orEmpty().isEmpty())

            assertTrue(viewModel.uiState.value.canUndo)
            viewModel.undo()
            assertEquals(input, viewModel.uiState.value.document)
        }

    @Test
    fun `an 적용 cancelled while the result is stored discards the stored file and changes nothing`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        val input = viewModel.uiState.value.document
        harness.repository.enhanceGate = CompletableDeferred()
        viewModel.direct.adoptTopaz()
        assertEquals(TopazPhase.Adopting, direct(viewModel).candidate?.topazPhase)

        viewModel.cancelSheet()
        // The store reads the result file: the session's folder waits for it.
        assertTrue(harness.topazRoot.listFiles().orEmpty().isNotEmpty())
        harness.repository.enhanceGate!!.complete(Unit)

        assertTrue(harness.repository.discarded.contains(harness.repository.enhancedPath(1)))
        assertEquals(input, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.canUndo)
        assertTrue(harness.topazRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an 적용 cancelled on the overlay and tried again - A finishing first is discarded, only B applies`() =
        runTest {
            val viewModel = planned(LOCAL_AND_TOPAZ)
            viewModel.applySheet()
            val (a, b) = cancelledAndRetried(viewModel)

            a.complete(Unit)
            assertTrue(harness.repository.discarded.contains(harness.repository.enhancedPath(1)))
            assertFalse(viewModel.uiState.value.canUndo)
            assertEquals(TopazPhase.Adopting, direct(viewModel).candidate?.topazPhase)

            b.complete(Unit)
            assertEquals(enhancedRef(2), viewModel.uiState.value.document!!.source)
            assertFalse(harness.repository.discarded.contains(harness.repository.enhancedPath(2)))
            assertEquals(1, topaz.submitCount)
        }

    @Test
    fun `an 적용 cancelled on the overlay and tried again - A finishing after B is discarded, B stays applied`() =
        runTest {
            val viewModel = planned(LOCAL_AND_TOPAZ)
            viewModel.applySheet()
            val (a, b) = cancelledAndRetried(viewModel)

            b.complete(Unit)
            assertEquals(enhancedRef(1), viewModel.uiState.value.document!!.source)

            a.complete(Unit)
            assertTrue(harness.repository.discarded.contains(harness.repository.enhancedPath(2)))
            assertEquals(enhancedRef(1), viewModel.uiState.value.document!!.source)
            assertNull(direct(viewModel).message)
            assertEquals(1, topaz.submitCount)
        }

    @Test
    fun `a cancelled 적용 failing late leaves the retry storing, and the retry's failure is its own`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        val input = viewModel.uiState.value.document
        val (a, b) = cancelledAndRetried(viewModel)

        harness.repository.failEnhance = true
        a.complete(Unit)
        assertNull(direct(viewModel).message)
        assertEquals(TopazPhase.Adopting, direct(viewModel).candidate?.topazPhase)

        b.complete(Unit)
        assertEquals(R.string.topaz_adopt_failed, direct(viewModel).message?.res)
        assertNull(direct(viewModel).candidate?.topazPhase)
        assertEquals(input, viewModel.uiState.value.document)
        assertTrue(harness.repository.enhanced.isEmpty())
        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `closing with a cancelled 적용 and its retry in flight keeps the result until the last one ends`() =
        runTest {
            val viewModel = planned(LOCAL_AND_TOPAZ)
            viewModel.applySheet()
            val input = viewModel.uiState.value.document
            val (a, b) = cancelledAndRetried(viewModel)

            viewModel.cancelSheet()
            b.complete(Unit)
            // A still reads the result file.
            assertTrue(harness.topazRoot.listFiles().orEmpty().isNotEmpty())
            a.complete(Unit)

            assertTrue(harness.repository.discarded.containsAll(List(2) { harness.repository.enhancedPath(it + 1) }))
            assertEquals(input, viewModel.uiState.value.document)
            assertTrue(harness.topazRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `closing the sheet after a result removes the session's Topaz files`() = runTest {
        val viewModel = planned(LOCAL_AND_TOPAZ)
        viewModel.applySheet()
        assertTrue(harness.topazRoot.listFiles().orEmpty().isNotEmpty())

        viewModel.cancelSheet()

        assertTrue(harness.topazRoot.listFiles().orEmpty().isEmpty())
        assertTrue(harness.repository.enhanced.isEmpty())
        assertNotEquals(Tool.Direct, viewModel.uiState.value.selectedTool)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun planned(plan: EditPlan): EditorViewModel {
        val viewModel = harness.opened()
        harness.planner.next(plan)
        viewModel.direct.submit("밝고 깨끗하게")
        return viewModel
    }

    private fun direct(viewModel: EditorViewModel) = viewModel.uiState.value.direct

    private fun enhancedRef(n: Int) = ImageRef(harness.repository.enhancedPath(n))

    /** Store A held, cancelled on the overlay, then store B held: the gates release each in any order. */
    private fun cancelledAndRetried(
        viewModel: EditorViewModel,
    ): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val a = CompletableDeferred<Unit>()
        val b = CompletableDeferred<Unit>()
        harness.repository.enhanceGate = a
        viewModel.direct.adoptTopaz()
        viewModel.direct.cancelWork()
        assertNull(direct(viewModel).candidate?.topazPhase)
        harness.repository.enhanceGate = b
        viewModel.direct.adoptTopaz()
        assertEquals(TopazPhase.Adopting, direct(viewModel).candidate?.topazPhase)
        return a to b
    }

    private fun android.graphics.Bitmap.centre(): Int = getPixel(width / 2, height / 2)

    /** JPEG round trips move a channel by a few levels. */
    private fun assertNear(expected: Int, actual: Int) {
        val near = abs(Color.red(expected) - Color.red(actual)) <= JPEG_SLACK &&
            abs(Color.green(expected) - Color.green(actual)) <= JPEG_SLACK &&
            abs(Color.blue(expected) - Color.blue(actual)) <= JPEG_SLACK
        assertTrue("expected ${Integer.toHexString(expected)}, was ${Integer.toHexString(actual)}", near)
    }

    private companion object {
        const val EXPOSURE = 0.2f
        const val TOLERANCE = 1e-4f
        const val JPEG_SLACK = 8
        val DENOISE = PlanStep.Topaz(TopazPurpose.Denoise, TopazOptions(scale = 1))
        val LOCAL_AND_TOPAZ = EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE), DENOISE))
        val TWO_LOCAL_AND_TOPAZ = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE),
                PlanStep.Adjust(AdjustKind.Temperature, 0.3f),
                DENOISE,
            ),
        )
        val LOCAL_CROP_TOPAZ = EditPlan(
            listOf(PlanStep.Adjust(AdjustKind.Exposure, EXPOSURE), PlanStep.Crop(CropRatio.Portrait4x5), DENOISE),
        )
    }
}
