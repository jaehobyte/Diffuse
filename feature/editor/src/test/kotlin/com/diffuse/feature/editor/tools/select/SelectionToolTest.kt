package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.tools.enhance.testTopazSettings
import com.diffuse.feature.editor.tools.enhance.testTopazStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
 * specs/selection_tool.md §10, as the tool stands without its sheet: a tap on the photo at the
 * AI level is the whole tool. Everything runs on `FakeSegmentationProvider`; no test outside
 * `core:ai` may touch a socket (CLAUDE.md hard limits).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionToolTest {

    private val provider = FakeSegmentationProvider(openDelayMs = 0)
    private lateinit var repository: RecordingRepository
    private lateinit var settings: Sam3Settings
    private lateinit var geminiSettings: GeminiSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = RecordingRepository()
        settings = Sam3Settings(ApplicationProvider.getApplicationContext())
        settings.update("http://localhost:8080", "token")
        geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext())
        geminiSettings.update("test-key")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- availability ----------------------------------------------------

    @Test
    fun `selection is disabled while the provider is unavailable`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.selection.enabled)
    }

    @Test
    fun `a tap the backend cannot serve explains itself instead of selecting`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val viewModel = viewModel()

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        val selection = viewModel.uiState.value.selection
        assertTrue(selection.points.isEmpty())
        assertEquals(0, provider.openCount)
        // Which of the two channels it uses depends on whether the backend ever answered; the
        // tests below pin that. What matters here is that the user is not left with nothing.
        assertTrue("the user was told nothing", selection.showSettings || selection.message != null)
    }

    @Test
    fun `an unconfigured provider opens the settings sheet rather than a snackbar`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Invalid("not configured")))
        val viewModel = viewModel()

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

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

            viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

            assertTrue("no way to fix a wrong address", viewModel.uiState.value.selection.showSettings)
        }

    @Test
    fun `a backend that worked before is treated as a blip, not a misconfiguration`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        viewModel.selectOnCanvas(0.2f, 0.2f, foreground = true)

        assertFalse(
            "a transient outage should not throw the settings sheet at the user",
            viewModel.uiState.value.selection.showSettings,
        )
        assertNotNull(viewModel.uiState.value.selection.message)
    }

    /**
     * A rejected token is a configuration problem however healthy the server is — and `/healthz`
     * needs no auth, so the backend answers, `everReady` is true, and the "it was working a
     * moment ago" branch would offer a snackbar and no way to fix it. Seen on the device: the
     * upload came back `401 unauthorized` and the tool had nothing to offer.
     */
    @Test
    fun `a rejected token offers the settings sheet even though the server is up`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        provider.setAvailability(Availability.Unavailable(AppError.Unauthorized))
        viewModel.selectOnCanvas(0.2f, 0.2f, foreground = true)

        assertTrue("no way to correct the token", viewModel.uiState.value.selection.showSettings)
    }

    @Test
    fun `a transient outage re-probes on the next tap instead of staying greyed`() = runTest {
        val viewModel = viewModel()
        provider.setAvailability(Availability.Unavailable(AppError.Unavailable))
        val probesBefore = provider.refreshCount

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        assertNotNull("the user is told", viewModel.uiState.value.selection.message)
        assertTrue("and the backend is re-probed rather than left greyed", provider.refreshCount > probesBefore)
    }

    // ---- sessions --------------------------------------------------------

    @Test
    fun `the session is opened once per editor session, not once per tap`() = runTest {
        val viewModel = viewModel()

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        viewModel.selectOnCanvas(0.2f, 0.2f, foreground = true)

        assertEquals(1, provider.openCount)
    }

    // These read the controller's own state: with a queued dispatcher the ViewModel's collector
    // has not run yet, and running it to idle would finish the upload being waited on.

    /** The first tap opens the session and waits on it, shown; a second tap in that window does not re-open. */
    @Test
    fun `the first tap opens the session, shows progress and is segmented once it is ready`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val slow = FakeSegmentationProvider(openDelayMs = 5_000)
        val viewModel = viewModel(slow)
        advanceUntilIdle()
        assertEquals("nothing is uploaded before the tap", 0, slow.openCount)

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        advanceTimeBy(1_000)
        assertTrue(viewModel.selection.state.value.preparing)
        assertTrue("the tap waits on the upload, and sees it", viewModel.selection.state.value.working)
        assertNotNull(viewModel.selection.state.value.pending)

        viewModel.selectOnCanvas(0.6f, 0.6f, foreground = true)
        assertEquals(
            "the newer tap replaces the kept one",
            PendingPoint(0.6f, 0.6f, true),
            viewModel.selection.state.value.pending,
        )
        advanceUntilIdle()

        val selection = viewModel.selection.state.value
        assertEquals("one upload, not two", 1, slow.openCount)
        assertNull(selection.pending)
        assertEquals(listOf(true), selection.labels)
        assertNotNull("the kept tap was segmented", selection.mask)
        assertFalse(selection.working)
        assertEquals("and committed", 1, repository.savedMasks.size)
    }

    @Test
    fun `cancelling while a tap is pending drops the tap`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val slow = FakeSegmentationProvider(openDelayMs = 5_000)
        val viewModel = viewModel(slow)
        advanceUntilIdle()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        viewModel.selection.cancelWork()
        advanceUntilIdle()

        val selection = viewModel.selection.state.value
        assertNull(selection.pending)
        assertNull(selection.mask)
        assertTrue(selection.points.isEmpty())
        assertFalse(selection.working)
        assertTrue("a session was left behind on the server", slow.openSessions.isEmpty())
    }

    /** A ticket, not a flag: re-opening must not make the abandoned upload's session wanted again. */
    @Test
    fun `an open given up on and started again keeps only the newer session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val slow = FakeSegmentationProvider(openDelayMs = 5_000)
        val viewModel = viewModel(slow)
        advanceUntilIdle()

        viewModel.selection.open(preview())
        advanceTimeBy(1_000)
        viewModel.selection.cancelWork()
        viewModel.selection.open(preview())
        advanceUntilIdle()

        val session = viewModel.uiState.value.selection.session
        assertNotNull(session)
        assertEquals("a session was left behind on the server", listOf(session), slow.openSessions)
    }

    /** An upload the tap started and that failed says so, and drops the tap it was carrying. */
    @Test
    fun `a failed open says so and keeps no pending tap`() = runTest {
        val viewModel = viewModel()
        provider.failNext(AppError.Unavailable)

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        val selection = viewModel.uiState.value.selection
        assertEquals(R.string.select_failed, selection.message)
        assertFalse(selection.preparing)
        assertNull(selection.pending)
        assertNull(selection.mask)
    }

    @Test
    fun `leaving the editor releases the session`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        assertEquals(1, provider.openSessions.size)

        viewModel.onLeave()

        assertTrue("the session was left open on the server", provider.openSessions.isEmpty())
    }

    // ---- taps ------------------------------------------------------------

    @Test
    fun `a tap produces a mask and commits it as one Mask op`() = runTest {
        val viewModel = viewModel()

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        val state = viewModel.uiState.value
        assertEquals(listOf(true), state.selection.labels)
        assertNotNull(state.selection.mask)
        assertEquals(1, repository.savedMasks.size)
        val masks = state.document!!.operations.filterIsInstance<Operation.Mask>()
        assertEquals(1, masks.size)
        assertEquals(masks.single().id, state.document.activeMaskId)
        assertTrue("one tap is one undo", state.canUndo)
    }

    @Test
    fun `a long press adds a background point and shrinks the mask`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        val before = MaskBitmaps.coverage(viewModel.uiState.value.selection.mask!!)

        viewModel.selectOnCanvas(0.52f, 0.5f, foreground = false)

        val selection = viewModel.uiState.value.selection
        assertEquals(listOf(true, false), selection.labels)
        assertTrue(MaskBitmaps.coverage(selection.mask!!) < before)
    }

    @Test
    fun `each refinement is one more history entry and the newest mask is the active one`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        viewModel.selectOnCanvas(0.52f, 0.5f, foreground = false)

        val document = viewModel.uiState.value.document!!
        val masks = document.operations.filterIsInstance<Operation.Mask>()
        assertEquals(2, masks.size)
        assertEquals(masks.last().id, document.activeMaskId)
        assertEquals(2, repository.savedMasks.size)
    }

    @Test
    fun `undo takes back one tap and ends the run`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        viewModel.selectOnCanvas(0.52f, 0.5f, foreground = false)
        val first = viewModel.uiState.value.document!!.operations.filterIsInstance<Operation.Mask>().first()

        viewModel.undo()

        val state = viewModel.uiState.value
        assertEquals(first.id, state.document!!.activeMaskId)
        assertTrue("the run's points are gone", state.selection.points.isEmpty())
        assertNull(state.selection.mask)

        // The next tap starts a new selection rather than refining stale points.
        viewModel.selectOnCanvas(0.3f, 0.3f, foreground = true)
        assertEquals(listOf(true), viewModel.uiState.value.selection.labels)
    }

    /** A run of only exclusions selects nothing, and nothing is what gets committed. */
    @Test
    fun `a long press alone selects nothing and commits nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = false)

        val state = viewModel.uiState.value
        assertEquals(listOf(false), state.selection.labels)
        assertNull(state.selection.mask)
        assertTrue(state.document!!.operations.isEmpty())
        assertTrue(repository.savedMasks.isEmpty())
    }

    // ---- failures --------------------------------------------------------

    @Test
    fun `a failed prompt keeps the mask the user already had`() = runTest {
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)
        val before = viewModel.uiState.value.selection.mask
        provider.failNext(AppError.Unavailable)

        viewModel.selectOnCanvas(0.2f, 0.2f, foreground = true)

        assertEquals(before, viewModel.uiState.value.selection.mask)
        assertNotNull(viewModel.uiState.value.selection.message)
        assertEquals(1, repository.savedMasks.size)
    }

    @Test
    fun `a failed mask write leaves the document untouched and says so`() = runTest {
        val viewModel = viewModel()
        repository.failMaskWrite = true

        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        val state = viewModel.uiState.value
        assertTrue(state.document!!.operations.isEmpty())
        assertNotNull(state.selection.message)
    }

    // ---- settings --------------------------------------------------------

    @Test
    fun `saving settings closes the sheet and re-probes`() = runTest {
        provider.setAvailability(Availability.Unavailable(AppError.Invalid("not configured")))
        val viewModel = viewModel()
        viewModel.selectOnCanvas(0.5f, 0.5f, foreground = true)

        viewModel.selection.saveSettings("http://10.0.2.2:8080", "tok", "AIza-key", "")

        assertFalse(viewModel.uiState.value.selection.showSettings)
        assertEquals("http://10.0.2.2:8080", settings.current().baseUrl)
        assertEquals("tok", settings.current().token)
        assertEquals("AIza-key", geminiSettings.config.value.apiKey)
        assertTrue(provider.refreshCount > 0)
    }

    /** D097: Gemini alone is a valid configuration, and saving it copies nothing into SAM 3's. */
    @Test
    fun `saving only a Gemini key leaves SAM 3 unconfigured`() = runTest {
        val viewModel = viewModel()

        viewModel.selection.saveSettings("", "", "AIza-only", "")

        assertEquals("AIza-only", geminiSettings.config.value.apiKey)
        assertEquals("AIza-only", viewModel.uiState.value.selection.geminiApiKey)
        assertEquals("", settings.current().baseUrl)
        assertEquals("", settings.current().token)
    }

    /** And the other way round: SAM 3 alone saves, and its token is not copied into Gemini's. */
    @Test
    fun `saving only SAM 3 leaves the Gemini key blank`() = runTest {
        val viewModel = viewModel()

        viewModel.selection.saveSettings("http://10.0.2.2:8080", "tok", "", "")

        assertEquals("http://10.0.2.2:8080", settings.current().baseUrl)
        assertEquals("tok", settings.current().token)
        assertEquals("", geminiSettings.config.value.apiKey)
    }

    private fun preview() = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    // ---- fixtures --------------------------------------------------------

    private fun viewModel(
        segmentation: FakeSegmentationProvider = provider,
    ) = EditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        repository = repository,
        renderer = FakeRenderer(),
        ai = EditorAi(
            segmentation,
            FakeEraseProvider(),
            FakeFillProvider(),
            FakeOutpaintProvider(),
            FakePlanProvider(),
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

    private class FakeRenderer : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit) =
            Result.Success(Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888))

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        val savedMasks = mutableListOf<String>()
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
        ): Result<ImageRef> = Result.Success(ImageRef("/projects/$projectId/erase_$eraseId.png"))

        override suspend fun saveFillResult(
            projectId: String,
            fillId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> = Result.Success(ImageRef("/projects/$projectId/fill_$fillId.png"))

        override suspend fun saveEnhanced(projectId: String, fileId: String, source: SourceImage): Result<ImageRef> =
            Result.Success(ImageRef("/p/enhanced_$fileId.jpg"))

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
        const val SIZE = 64
    }
}
