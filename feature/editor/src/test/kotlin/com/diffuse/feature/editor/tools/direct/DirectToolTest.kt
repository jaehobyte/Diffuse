package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeAutoEnhanceProvider
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakePortraitDetector
import com.diffuse.core.ai.FakePromptSuggestionProvider
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
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

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val eraser = FakeEraseProvider()
    private val filler = FakeFillProvider()
    private val planner = FakePlanProvider()
    private val suggester = FakePromptSuggestionProvider()
    private val renderer = FakeRenderer()
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
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true))))

        viewModel.direct.submit(REQUEST)

        assertTrue(viewModel.uiState.value.direct.notUnderstood)
        assertNull(viewModel.uiState.value.direct.plan)
    }

    @Test
    fun `a second submit replaces the first plan`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)
        val replacement = EditPlan(listOf(PlanStep.Select("하늘")))
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

    // ---- §9.3, running ----------------------------------------------------

    @Test
    fun `적용 runs the plan, one history entry per step`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        assertEquals(2, document.operations.size)
        assertNotNull(document.activeMaskId)
        assertEquals(
            document.activeMaskId,
            document.operations.filterIsInstance<Operation.Adjust>().single().maskId,
        )
        // One entry per step: two undos peel the plan apart.
        viewModel.undo()
        assertEquals(1, viewModel.uiState.value.document!!.operations.size)
        viewModel.undo()
        assertEquals(0, viewModel.uiState.value.document!!.operations.size)
    }

    // ---- §4.1 the crop hand-off (T58) ------------------------------------

    @Test
    fun `a plan that ends with a crop opens the 자르기 tool on what it committed`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Crop(CropRatio.Story9x16))))
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertEquals(Tool.Crop, viewModel.uiState.value.selectedTool)
        val crop = viewModel.uiState.value.document!!.crop()!!
        assertTrue("a 9:16 crop is taller than it is wide", crop.rect.width() < crop.rect.height())
        // The chip stays selected, so dragging cannot lose the ratio the model chose.
        assertEquals(AspectPreset.NineSixteen, viewModel.uiState.value.cropState.preset)
    }

    @Test
    fun `a plan with no crop leaves every tool closed`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.4f, masked = false))))
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `a run that stops before its crop opens no tool`() = runTest {
        val viewModel = opened()
        // "없음" is the fake's phrase that finds nothing, so the run stops at step 0.
        planner.next(
            EditPlan(listOf(PlanStep.Select("없음"), PlanStep.Crop(CropRatio.Story9x16))),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        // The Select failed, so the crop never ran; opening 자르기 would be a lie.
        assertNull(viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.document!!.crop())
    }

    @Test
    fun `the sheet closes when the run ends`() = runTest {
        val viewModel = opened()
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertNull(viewModel.uiState.value.direct.plan)
        assertFalse(viewModel.uiState.value.direct.running)
    }

    @Test
    fun `a run containing a Select clears the selection session`() = runTest {
        val viewModel = opened()
        // The 선택 tool opened first, so it is holding a session.
        viewModel.onToolClick(Tool.Select)
        viewModel.cancelSheet()
        assertNotNull(viewModel.uiState.value.selection.session)
        viewModel.onToolClick(Tool.Direct)
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertNull(viewModel.uiState.value.selection.session)
    }

    @Test
    fun `a Select that finds nothing names the word and commits nothing`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Select("없음"))))
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        val message = viewModel.uiState.value.direct.message!!
        assertEquals(R.string.direct_not_found, message.res)
        assertEquals("없음", message.arg)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `a failure mid-run keeps the steps before it`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
                    PlanStep.Select("나무"),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)
        segmentation.failNext(AppError.Unavailable)

        viewModel.applySheet()

        assertEquals(1, viewModel.uiState.value.document!!.operations.size)
        assertEquals(R.string.direct_failed, viewModel.uiState.value.direct.message?.res)
    }

    // ---- T53: the shape a mixed plan leaves behind -------------------------

    @Test
    fun `a select, erase and global adjust land in that order with the adjust unmasked`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("bus"),
                    PlanStep.Erase,
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f, masked = false),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

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
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f, masked = false),
                    PlanStep.Select("chair"),
                    PlanStep.Fill("a red umbrella"),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

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
    fun `each step of a mixed plan is its own history entry`() = runTest {
        val viewModel = opened()
        planner.next(
            EditPlan(
                listOf(
                    PlanStep.Select("bus"),
                    PlanStep.Erase,
                    PlanStep.Adjust(AdjustKind.Saturation, 0.2f, masked = false),
                ),
            ),
        )
        viewModel.direct.submit(REQUEST)
        viewModel.applySheet()

        viewModel.undo()
        assertTrue(viewModel.uiState.value.document!!.operations.none { it is Operation.Adjust })
        viewModel.undo()
        assertTrue(viewModel.uiState.value.document!!.generativeErases().isEmpty())
        viewModel.undo()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document!!.operations)
    }

    @Test
    fun `the erase in a plan is told what the Select was looking for`() = runTest {
        val viewModel = opened()
        planner.next(EditPlan(listOf(PlanStep.Select("bus"), PlanStep.Erase)))
        viewModel.direct.submit(REQUEST)

        viewModel.applySheet()

        assertEquals("bus", eraser.lastHint)
    }

    // ---- §14, sentence suggestions ----------------------------------------

    @Test
    fun `opening the sheet sends no suggestion request`() = runTest {
        val viewModel = opened()

        val state = viewModel.uiState.value.direct
        assertEquals(0, suggester.suggestCount)
        assertEquals(SuggestionPhase.Idle, state.suggestions.phase)
        assertTrue(state.suggestions.frameReady)
        assertTrue(state.showSuggestions)
    }

    @Test
    fun `one suggestion tap is one call, and repeated taps coalesce`() = runTest {
        val viewModel = opened()
        suggester.hold()

        viewModel.direct.suggestions.find()
        viewModel.direct.suggestions.find()
        assertEquals(SuggestionPhase.Loading, viewModel.uiState.value.direct.suggestions.phase)
        suggester.release()

        val suggestions = viewModel.uiState.value.direct.suggestions
        assertEquals(1, suggester.suggestCount)
        assertEquals(SuggestionPhase.Tailored, suggestions.phase)
        assertEquals(FakePromptSuggestionProvider.DEFAULT, suggestions.ids)
    }

    @Test
    fun `a suggestion is asked of the frame on screen`() = runTest {
        val viewModel = opened()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)

        viewModel.direct.suggestions.find()

        assertEquals(ADJUSTED, suggester.lastImage!!.getPixel(0, 0))
    }

    @Test
    fun `a finished suggestion is reused after the sheet is reopened`() = runTest {
        val viewModel = opened()
        viewModel.direct.suggestions.find()
        viewModel.cancelSheet()

        viewModel.onToolClick(Tool.Direct)
        viewModel.direct.suggestions.find()

        assertEquals(1, suggester.suggestCount)
        assertEquals(SuggestionPhase.Tailored, viewModel.uiState.value.direct.suggestions.phase)
    }

    @Test
    fun `picking a suggestion only fills the bar`() = runTest {
        val viewModel = opened()
        viewModel.direct.suggestions.find()

        viewModel.direct.pickSuggestion(PICKED)

        val state = viewModel.uiState.value
        assertEquals(PICKED, state.direct.request)
        assertEquals(RequestSource.Suggestion, state.direct.requestSource)
        assertTrue("an unedited suggestion can still be swapped", state.direct.showSuggestions)
        assertEquals(0, planner.planCount)
        assertNull(state.direct.plan)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
    }

    @Test
    fun `a picked suggestion plans once on submit and applies through the plan`() = runTest {
        val viewModel = opened()
        viewModel.direct.pickSuggestion(PICKED)
        planner.next(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.3f, masked = false))))

        viewModel.direct.submit(PICKED)
        assertEquals(1, planner.planCount)
        viewModel.applySheet()

        assertEquals(1, viewModel.uiState.value.document!!.operations.size)
    }

    @Test
    fun `changing the sentence after a suggestion plan makes it unappliable`() = runTest {
        val viewModel = opened()
        viewModel.direct.pickSuggestion(PICKED)
        viewModel.direct.submit(PICKED)
        assertTrue(viewModel.uiState.value.direct.canApply)

        viewModel.direct.setRequest("사진 전체를 많이 밝게 해줘")

        assertNull(viewModel.uiState.value.direct.plan)
        assertFalse(viewModel.uiState.value.direct.canApply)
        viewModel.applySheet()
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `a late plan for the sentence before a suggestion swap is dropped`() = runTest {
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
    fun `typing while a suggestion request runs keeps the text and hides the list`() = runTest {
        val viewModel = opened()
        suggester.hold()
        viewModel.direct.suggestions.find()

        viewModel.direct.setRequest("하늘을")
        suggester.release()

        val state = viewModel.uiState.value.direct
        assertEquals("하늘을", state.request)
        assertFalse(state.showSuggestions)
        assertEquals(SuggestionPhase.Tailored, state.suggestions.phase)
        // Clearing the bar brings the same answer back without a second call.
        viewModel.direct.setRequest("")
        assertTrue(viewModel.uiState.value.direct.showSuggestions)
        assertEquals(1, suggester.suggestCount)
    }

    @Test
    fun `cancelling a suggestion request after a pick keeps the sentence and the document`() = runTest {
        val viewModel = opened()
        suggester.hold()
        viewModel.direct.suggestions.find()
        viewModel.direct.pickSuggestion(PICKED)
        assertEquals(SuggestionPhase.Loading, viewModel.uiState.value.direct.suggestions.phase)

        viewModel.direct.suggestions.cancel()
        suggester.release()

        val state = viewModel.uiState.value
        assertEquals(SuggestionPhase.Idle, state.direct.suggestions.phase)
        assertEquals(PICKED, state.direct.request)
        assertEquals(RequestSource.Suggestion, state.direct.requestSource)
        assertEquals(Tool.Direct, state.selectedTool)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun `submitting drops a suggestion request still in flight`() = runTest {
        val viewModel = opened()
        suggester.hold(ignoreCancellation = true)
        viewModel.direct.suggestions.find()

        viewModel.direct.submit(REQUEST)
        suggester.release()

        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)
        assertEquals(1, planner.planCount)
    }

    @Test
    fun `closing the sheet drops a late suggestion answer`() = runTest {
        val viewModel = opened()
        suggester.hold(ignoreCancellation = true)
        viewModel.direct.suggestions.find()

        viewModel.cancelSheet()
        suggester.release()

        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)
        viewModel.onToolClick(Tool.Direct)
        viewModel.direct.suggestions.find()
        assertEquals(2, suggester.suggestCount)
    }

    @Test
    fun `undo and redo drop suggestions asked of another document`() = runTest {
        val viewModel = opened()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.onAdjustFinished()
        viewModel.direct.suggestions.find()

        viewModel.undo()
        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)
        viewModel.redo()
        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)

        viewModel.direct.suggestions.find()
        assertEquals("never re-asked on its own", 2, suggester.suggestCount)
    }

    @Test
    fun `a late suggestion for an older document is dropped`() = runTest {
        val viewModel = opened()
        suggester.hold(ignoreCancellation = true)
        viewModel.direct.suggestions.find()

        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        suggester.release()

        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)
    }

    @Test
    fun `render A still on screen for document B sends no suggestion request`() = runTest {
        val viewModel = opened()
        renderer.gate = CompletableDeferred()

        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.direct.suggestions.find()

        assertFalse(viewModel.uiState.value.direct.suggestions.frameReady)
        assertEquals(0, suggester.suggestCount)
        renderer.gate!!.complete(Unit)
        assertTrue(viewModel.uiState.value.direct.suggestions.frameReady)
    }

    @Test
    fun `a failed render sends no suggestion request`() = runTest {
        val viewModel = opened()
        renderer.fail = true

        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.direct.suggestions.find()

        assertFalse(viewModel.uiState.value.direct.suggestions.frameReady)
        assertEquals(0, suggester.suggestCount)
    }

    @Test
    fun `a Gemini settings change drops suggestions and the request in flight`() = runTest {
        val viewModel = opened()
        suggester.hold(ignoreCancellation = true)
        viewModel.direct.suggestions.find()

        geminiSettings.update("another-key")
        suggester.release()

        assertEquals(SuggestionPhase.Idle, viewModel.uiState.value.direct.suggestions.phase)
        assertEquals(1, suggester.suggestCount)
    }

    @Test
    fun `a suggestion failure says so once and falls back to the examples`() = runTest {
        val viewModel = opened()
        suggester.failNext(AppError.Unavailable)

        viewModel.direct.suggestions.find()

        val state = viewModel.uiState.value.direct
        assertEquals(SuggestionPhase.Failed, state.suggestions.phase)
        assertEquals(R.string.direct_suggest_failed, state.message?.res)
        assertTrue(state.showSuggestions)
        assertEquals("no automatic retry", 1, suggester.suggestCount)
        viewModel.direct.suggestions.find()
        assertEquals(SuggestionPhase.Tailored, viewModel.uiState.value.direct.suggestions.phase)
    }

    @Test
    fun `nothing fitting is an empty suggestion answer, not a failure`() = runTest {
        val viewModel = opened()
        suggester.next(emptyList())

        viewModel.direct.suggestions.find()

        val state = viewModel.uiState.value.direct
        assertEquals(SuggestionPhase.Empty, state.suggestions.phase)
        assertNull(state.message)
    }

    @Test
    fun `hidden suggestions stay hidden for the session, whatever arrives`() = runTest {
        val viewModel = opened()
        suggester.hold()
        viewModel.direct.suggestions.find()

        viewModel.direct.suggestions.hide()
        suggester.release()

        assertFalse(viewModel.uiState.value.direct.showSuggestions)
        viewModel.cancelSheet()
        viewModel.onToolClick(Tool.Direct)
        assertTrue(viewModel.uiState.value.direct.showSuggestions)
    }

    @Test
    fun `the suggestion catalog keeps one direction per group`() {
        assertEquals(
            listOf(PromptSuggestionId.Warm, PromptSuggestionId.Brighten),
            PromptSuggestionId.distinctDirections(
                listOf(
                    PromptSuggestionId.Warm,
                    PromptSuggestionId.Cool,
                    PromptSuggestionId.FilmWarm,
                    PromptSuggestionId.Brighten,
                    PromptSuggestionId.LiftShadows,
                ),
            ),
        )
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
            monetSettings,
            FakeAutoEnhanceProvider(),
            FakeMatchStyleProvider(),
            FakePortraitDetector(),
            com.diffuse.core.ai.FakeSkinRetouchProvider(),
            com.diffuse.core.ai.FakeFaceRegionAnalyzer(),
            com.diffuse.core.ai.retouch.server.RetouchServerSettings(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            ),
            suggester,
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    private fun assertNotNull(value: Any?) =
        assertTrue("expected a value, got null", value != null)

    /** Resolves any mask to a full-frame one, as the erase tool's test does. */
    /** T70: colour-coded by whether the document still carries adjustments, as the tool tests are. */
    private class FakeRenderer : Renderer {
        /** §14: a render that fails keeps the old frame on screen; one held keeps it in flight. */
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap> {
            gate?.await()
            return if (fail) Result.Failure(AppError.Unavailable) else Result.Success(frame(document))
        }

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(frame(document))

        private fun frame(document: EditDocument): Bitmap =
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
                eraseColor(
                    if (document.operations.any { it is Operation.Adjust }) ADJUSTED else PLAIN,
                )
            }

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? =
            document.mask(maskId)?.let {
                Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply {
                    for (pixel in 0 until SIZE * SIZE) {
                        setPixel(pixel % SIZE, pixel / SIZE, OPAQUE shl ALPHA_SHIFT)
                    }
                }
            }
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
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        const val PICKED = "사진 전체를 조금 더 밝게 해줘"
        const val SIZE = 32
        val PLAIN = android.graphics.Color.rgb(40, 50, 60)
        val ADJUSTED = android.graphics.Color.rgb(200, 210, 220)
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
