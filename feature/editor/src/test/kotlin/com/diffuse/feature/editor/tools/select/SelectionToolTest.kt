package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * specs/selection_tool.md §10. Everything runs on `FakeSegmentationProvider`; no test outside
 * `core:ai` may touch a socket (CLAUDE.md hard limits).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionToolTest {

    private val provider = FakeSegmentationProvider(openDelayMs = 0)
    private val eraseProvider = FakeEraseProvider()
    private lateinit var repository: RecordingRepository
    private lateinit var settings: Sam3Settings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = RecordingRepository()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- availability ----------------------------------------------------

    @Test
    fun `the tool is disabled while the provider is unavailable`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.selection.enabled)
    }

    @Test
    fun `tapping an unreachable tool explains itself instead of opening`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Select)

        val selection = viewModel.uiState.value.selection
        assertNull("the selection sheet must not open", viewModel.uiState.value.selectedTool)
        // Which of the two channels it uses depends on whether the backend ever answered; the
        // two tests above pin that. What matters here is that the user is not left with nothing.
        assertTrue(
            "the user was told nothing",
            selection.showSettings || selection.message != null,
        )
    }

    @Test
    fun `an unconfigured provider opens the settings sheet rather than a snackbar`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Invalid("not configured")))
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Select)

        assertTrue(viewModel.uiState.value.selection.showSettings)
        assertNull(viewModel.uiState.value.selection.message)
    }

    /**
     * A wrong address and a down server are the same `Unavailable` to the app, but only one of
     * them the user can fix — and the settings sheet was reachable only while the URL was
     * *blank*. Ship a default that does not resolve (the emulator's 10.0.2.2 on a real phone)
     * and the tool is greyed forever with no way in. Found on the first device run.
     */
    @Test
    fun `a backend that has never answered offers the settings sheet, not just a snackbar`() =
        runTest {
            provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
            val viewModel = viewModel()

            viewModel.onToolClick(Tool.Select)

            assertTrue(
                "no way to fix a wrong address",
                viewModel.uiState.value.selection.showSettings,
            )
        }

    @Test
    fun `a backend that worked before is treated as a blip, not a misconfiguration`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.cancelSheet()

        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        viewModel.onToolClick(Tool.Select)

        assertFalse(
            "a transient outage should not throw the settings sheet at the user",
            viewModel.uiState.value.selection.showSettings,
        )
        assertNotNull(viewModel.uiState.value.selection.message)
    }

    // ---- sessions --------------------------------------------------------

    @Test
    fun `the session is opened once per editor session, not once per sheet`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.Select)
        viewModel.cancelSheet()
        viewModel.onToolClick(Tool.Select)

        assertEquals(1, provider.openCount)
    }

    /**
     * The server's session cache is 4 deep with a 120s TTL. A second upload while the first is
     * still in flight leaks a session server-side and, repeated, trips the rate limiter — which
     * is exactly what happened on the first device run.
     */
    /**
     * Giving up on a slow upload does not un-create the session the server already made. Left
     * unreleased, four of those fill its cache and the fifth attempt is rate-limited — which is
     * what took the tool offline on the first device run.
     */
    @Test
    fun `cancelling a slow open releases the session the server already made`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val slow = FakeSegmentationProvider(openDelayMs = 5_000)
        val viewModel = viewModel(slow)
        advanceUntilIdle()

        viewModel.onToolClick(Tool.Select)
        advanceTimeBy(1_000)
        viewModel.selection.cancelWork()
        advanceUntilIdle()

        assertTrue(
            "a session was left behind on the server",
            slow.openSessions.isEmpty(),
        )
    }

    @Test
    fun `leaving the editor releases the session`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        assertEquals(1, provider.openSessions.size)

        viewModel.onLeave()

        assertTrue("the session was left open on the server", provider.openSessions.isEmpty())
    }

    /**
     * 429 and a dropped connection both arrive as `Unavailable`, and both are transient. Latching
     * the tool off until the settings change leaves the user with a permanently grey button.
     */
    @Test
    fun `a transient outage re-probes on the next tap instead of staying greyed`() = runTest {
        val viewModel = viewModel()
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val probesBefore = provider.refreshCount

        viewModel.onToolClick(Tool.Select)

        assertNotNull("the user is told", viewModel.uiState.value.selection.message)
        assertTrue(
            "and the backend is re-probed rather than left greyed",
            provider.refreshCount > probesBefore,
        )
    }

    // ---- points ----------------------------------------------------------

    @Test
    fun `a tap produces a mask`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)

        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        val selection = viewModel.uiState.value.selection
        assertEquals(1, selection.points.size)
        assertEquals(listOf(true), selection.labels)
        assertNotNull(selection.mask)
    }

    @Test
    fun `a long press adds a background point and shrinks the mask`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val before = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.addPoint(0.52f, 0.5f, foreground = false)

        val selection = viewModel.uiState.value.selection
        assertEquals(listOf(true, false), selection.labels)
        assertTrue(MaskBitmaps.coverage(selection.mask!!) < before)
    }

    @Test
    fun `undo drops the last point and re-segments`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val oneCoverage = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)
        viewModel.selection.addPoint(0.52f, 0.5f, foreground = false)

        viewModel.undo()

        val selection = viewModel.uiState.value.selection
        assertEquals(1, selection.points.size)
        assertEquals(oneCoverage, MaskBitmaps.coverage(selection.mask!!), 0f)
    }

    @Test
    fun `undoing the only point clears the mask instead of prompting with nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.undo()

        assertNull(viewModel.uiState.value.selection.mask)
        assertTrue(viewModel.uiState.value.selection.points.isEmpty())
    }

    @Test
    fun `undo leaves the document alone while the sheet is open`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.undo()

        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    // ---- 반전 / 지우기 -----------------------------------------------------

    @Test
    fun `invert flips the mask and is its own inverse`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val original = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.invert()
        val inverted = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)
        viewModel.selection.invert()

        assertEquals(1f - original, inverted, 0.001f)
        assertEquals(original, MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!), 0f)
    }

    @Test
    fun `clear drops the selection but keeps the session`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.selection.clear()

        val selection = viewModel.uiState.value.selection
        assertNull(selection.mask)
        assertTrue(selection.points.isEmpty())
        assertNotNull(selection.session)
    }

    // ---- apply / cancel --------------------------------------------------

    @Test
    fun `apply writes one mask file and one Mask op, and makes it active`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.applySheet()

        val document = viewModel.uiState.value.document!!
        val masks = document.operations.filterIsInstance<Operation.Mask>()
        assertEquals(1, masks.size)
        assertEquals(masks.single().id, document.activeMaskId)
        assertEquals(1, repository.savedMasks.size)
        assertEquals(masks.single().id, repository.savedMasks.single())
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `a failed mask write leaves the sheet open with the selection intact`() = runTest {
        repository.failMaskWrite = true
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.applySheet()

        assertEquals(Tool.Select, viewModel.uiState.value.selectedTool)
        assertNotNull(viewModel.uiState.value.selection.mask)
        assertNotNull(viewModel.uiState.value.selection.message)
        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
    }

    @Test
    fun `cancel leaves the document untouched`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.cancelSheet()

        assertEquals(emptyList<Operation>(), viewModel.uiState.value.document?.operations)
        assertNull(viewModel.uiState.value.selection.mask)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `배경 지우기 writes the mask and the cut-out as one history entry`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        viewModel.applyCutOut()

        val document = viewModel.uiState.value.document!!
        val maskId = document.operations.filterIsInstance<Operation.Mask>().single().id
        assertEquals(maskId, document.cutOuts().single().maskId)
        assertTrue(document.hasAlpha)
        assertNull(viewModel.uiState.value.selectedTool)
    }

    @Test
    fun `undo takes back the mask and the cut-out together`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        viewModel.applyCutOut()

        viewModel.undo()

        val document = viewModel.uiState.value.document!!
        assertEquals(emptyList<Operation>(), document.operations)
        assertNull(document.activeMaskId)
        assertFalse(document.hasAlpha)
    }

    // ---- prompt (specs/prompt_input.md 4) --------------------------------

    @Test
    fun `a phrase segments every instance and merges them as one`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)

        viewModel.selection.submitPhrase("사람")

        val selection = viewModel.uiState.value.selection
        assertNotNull(selection.mask)
        // The fake returns two circles; their union is what merged.
        assertTrue(MaskBitmaps.coverage(selection.mask!!) > 0f)
        assertFalse(selection.notFound)
    }

    @Test
    fun `the bar clears only after a successful merge`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.setPhrase("사람")

        viewModel.selection.submitPhrase("사람")

        assertEquals("", viewModel.uiState.value.selection.phrase)
    }

    @Test
    fun `a phrase adds to what points already selected`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val afterPoint = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.submitPhrase("하늘")

        assertTrue(MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!) > afterPoint)
    }

    @Test
    fun `a phrase in subtract mode takes its instances out`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.submitPhrase("사람")
        val added = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.setMode(MergeMode.Subtract)
        viewModel.selection.submitPhrase("사람")

        assertNull(viewModel.uiState.value.selection.mask)
        assertTrue(added > 0f)
    }

    @Test
    fun `an absent concept is a hint, not a failure, and keeps the phrase`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)

        viewModel.selection.submitPhrase("없음")

        val selection = viewModel.uiState.value.selection
        assertTrue(selection.notFound)
        assertNull(selection.message)
        assertEquals("없음", selection.phrase)
    }

    @Test
    fun `a failed phrase keeps the text so the user can retry`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        provider.failNext(AppError.Unavailable)

        viewModel.selection.submitPhrase("사람")

        val selection = viewModel.uiState.value.selection
        assertEquals("사람", selection.phrase)
        assertNotNull(selection.message)
        assertNull(selection.mask)
    }

    @Test
    fun `a blank phrase never reaches the provider`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)

        viewModel.selection.submitPhrase("   ")

        assertFalse(viewModel.uiState.value.selection.phraseBusy)
        assertNull(viewModel.uiState.value.selection.mask)
    }

    @Test
    fun `cancelling a phrase leaves what was already accumulated`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val before = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.cancelWork()

        val selection = viewModel.uiState.value.selection
        assertEquals(before, MaskBitmaps.coverage(selection.mask!!), 0f)
        assertFalse(selection.working)
    }

    // ---- failures --------------------------------------------------------

    @Test
    fun `a failed prompt keeps the mask the user already had`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val before = viewModel.uiState.value.selection.mask
        provider.failNext(AppError.Unavailable)

        viewModel.selection.addPoint(0.2f, 0.2f, foreground = true)

        assertEquals(before, viewModel.uiState.value.selection.mask)
        assertNotNull(viewModel.uiState.value.selection.message)
    }

    // ---- merging (specs/selection_tool.md 4) -----------------------------

    @Test
    fun `switching mode ends the run, so the next tap builds a new selection`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.3f, 0.3f, foreground = true)
        val added = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.setMode(MergeMode.Subtract)

        val selection = viewModel.uiState.value.selection
        assertTrue(selection.points.isEmpty())
        assertEquals(added, MaskBitmaps.coverage(selection.base!!), 0f)
    }

    @Test
    fun `a subtract-mode tap takes its own result out of the selection`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val added = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.setMode(MergeMode.Subtract)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)

        assertNull(viewModel.uiState.value.selection.mask)
        assertTrue(added > 0f)
    }

    @Test
    fun `undo with an empty run takes back one whole merge`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.3f, 0.3f, foreground = true)
        val afterFirst = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)
        viewModel.selection.setMode(MergeMode.Subtract)
        viewModel.selection.setMode(MergeMode.Add)
        viewModel.selection.addPoint(0.7f, 0.7f, foreground = true)
        viewModel.undo()

        // The run is empty now, so the next undo unwinds the merge itself.
        viewModel.undo()

        assertEquals(afterFirst, MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!), 0f)
    }

    @Test
    fun `invert flips the whole accumulated selection`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.selection.addPoint(0.5f, 0.5f, foreground = true)
        val before = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selection.invert()

        val selection = viewModel.uiState.value.selection
        assertEquals(1f - before, MaskBitmaps.coverage(selection.mask!!), 0.001f)
        assertEquals(selection.mask, selection.base)
    }

    @Test
    fun `saving settings closes the sheet and re-probes`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Invalid("not configured")))
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)

        viewModel.selection.saveSettings("http://10.0.2.2:8080", "tok")

        assertFalse(viewModel.uiState.value.selection.showSettings)
        assertEquals("http://10.0.2.2:8080", settings.current().baseUrl)
        assertTrue(provider.refreshCount > 0)
    }

    // ---- fixtures --------------------------------------------------------

    private fun viewModel(
        segmentation: FakeSegmentationProvider = provider,
    ) = EditorViewModel(
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(segmentation, eraseProvider, FakeSpeechInput(), settings),
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    private class FakeRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        val savedMasks = mutableListOf<String>()
        val savedErases = mutableListOf<String>()
        var failMaskWrite = false
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
        ): Result<ImageRef> {
            if (failMaskWrite) return Result.Failure(AppError.Io(java.io.IOException("disk full")))
            savedMasks += maskId
            return Result.Success(ImageRef("/projects/$projectId/mask_$maskId.png"))
        }

        override suspend fun saveEraseResult(
            projectId: String,
            eraseId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> {
            savedErases += eraseId
            return Result.Success(ImageRef("/projects/$projectId/erase_$eraseId.png"))
        }

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val SIZE = 64
    }
}
