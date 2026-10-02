package com.diffuse.feature.editor.tools.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.FakeAutoEnhanceProvider
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFaceRegionAnalyzer
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakePortraitDetector
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.FakeSkinRetouchProvider
import com.diffuse.core.ai.PortraitResult
import com.diffuse.core.ai.SkinRetouchKind
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
import com.diffuse.core.ai.retouch.server.RetouchServerSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.model.RetouchKind
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.CoroutineContext

/**
 * specs/skin_retouch.md §3–§5, pipeline §4–§7, skin_retouch_validation.md §4 (ViewModel, 경합,
 * Lifecycle). The whole session through the real ViewModel over fakes: what is uploaded, when, what
 * reaches history, and what a late answer is allowed to touch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkinRetouchToolTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val provider = FakeSkinRetouchProvider(
        executionLocation = com.diffuse.core.ai.ExecutionLocation.RetouchServer,
    )
    private val analyzer = FakeFaceRegionAnalyzer(faces = listOf(FACE))
    private val renderer = RecordingRenderer()
    private val repository = RecordingRepository()
    private lateinit var retouchSettings: RetouchServerSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context.getSharedPreferences("retouch_server_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        retouchSettings = RetouchServerSettings(context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- entering ------------------------------------------------------------------------------

    @Test
    fun `entering analyses the working base locally and uploads nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.SkinRetouch)

        val state = viewModel.uiState.value
        assertEquals(Tool.SkinRetouch, state.selectedTool)
        assertEquals(SkinAnalysis.Ready, state.skin.analysis)
        assertEquals(1, state.skin.faces.size)
        assertEquals(listOf(CANVAS to CANVAS), analyzer.analyzedSizes)
        assertEquals(0, provider.prepareCount)
        assertFalse(state.canUndo)
        assertFalse(state.skin.canApply)
    }

    @Test
    fun `no face and an analysis failure are told apart, and the failure retries`() = runTest {
        analyzer.faces = emptyList()
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.SkinRetouch)
        assertEquals(SkinAnalysis.NoFace, viewModel.uiState.value.skin.analysis)
        viewModel.cancelSheet()

        analyzer.faces = listOf(FACE)
        analyzer.failAnalysis = true
        viewModel.onToolClick(Tool.SkinRetouch)
        assertEquals(SkinAnalysis.Failed, viewModel.uiState.value.skin.analysis)

        analyzer.failAnalysis = false
        viewModel.skin.retryAnalysis()
        assertEquals(SkinAnalysis.Ready, viewModel.uiState.value.skin.analysis)
    }

    @Test
    fun `a kind the server has not enabled has no slider and cannot be prepared`() = runTest {
        provider.setSupportedKinds(setOf(SkinRetouchKind.Blemish))
        val viewModel = openSheet()

        viewModel.skin.setStrength(SkinRetouchKind.Shine, 80)

        val skin = viewModel.uiState.value.skin
        assertFalse(skin.kindEnabled(SkinRetouchKind.Shine))
        assertEquals(R.string.skin_retouch_unsupported, skin.kindReason(SkinRetouchKind.Shine))
        assertEquals(0, skin.strengths[SkinRetouchKind.Shine])
        assertFalse(skin.canPreview)
    }

    @Test
    fun `a small face disables every kind with its reason`() = runTest {
        analyzer.faces = listOf(Rect(250, 250, 400, 400))
        val viewModel = openSheet()

        val skin = viewModel.uiState.value.skin
        SkinRetouchKind.entries.forEach {
            assertFalse(skin.kindEnabled(it))
            assertEquals(R.string.skin_retouch_face_too_small, skin.kindReason(it))
        }
    }

    // ---- preview and local strength -------------------------------------------------------------

    @Test
    fun `a strength alone asks for 미리보기 and sends nothing`() = runTest {
        val viewModel = openSheet()

        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)

        val skin = viewModel.uiState.value.skin
        assertTrue(skin.needsPreview)
        assertTrue(skin.canPreview)
        assertFalse(skin.canApply)
        assertEquals(0, provider.prepareCount)
    }

    @Test
    fun `미리보기 prepares only the kinds above zero, once each, and sliders afterwards stay local`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.setStrength(SkinRetouchKind.DarkCircles, 25)

        viewModel.skin.preview()

        assertEquals(listOf(SkinRetouchKind.Blemish, SkinRetouchKind.DarkCircles), provider.preparedKinds)
        val draft = assertDraft(viewModel)
        listOf(0, 25, 50, 75, 100).forEach { viewModel.skin.setStrength(SkinRetouchKind.Blemish, it) }
        assertEquals(2, provider.prepareCount)
        assertTrue(viewModel.uiState.value.skin.canApply)
        assertFalse(viewModel.uiState.value.canUndo)
        assertTrue(renderer.transients.isNotEmpty())
        assertNotNull(draft)
    }

    @Test
    fun `a new kind without a candidate keeps the old draft but disables 적용`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()

        viewModel.skin.setStrength(SkinRetouchKind.Shine, 40)

        val skin = viewModel.uiState.value.skin
        assertTrue(skin.needsPreview)
        assertFalse(skin.canApply)
        assertNotNull(viewModel.skin.draftDocument)
    }

    @Test
    fun `no change is a message and never an applicable result`() = runTest {
        provider.noChangeKinds = setOf(SkinRetouchKind.Blemish)
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 100)

        viewModel.skin.preview()

        val skin = viewModel.uiState.value.skin
        assertEquals(R.string.skin_retouch_no_change, skin.message)
        assertFalse(skin.canApply)
        viewModel.applySheet()
        assertFalse(viewModel.uiState.value.canUndo)
        assertEquals(0, repository.retouchSaves)
    }

    @Test
    fun `failures are told apart and keep the values for a retry with the same settings`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 60)
        val table = listOf(
            AppError.Unauthorized to R.string.skin_retouch_unauthorized,
            AppError.Unavailable to R.string.skin_retouch_not_ready,
            AppError.Io(SocketTimeoutException("timeout")) to R.string.skin_retouch_timeout,
            AppError.Io(IOException("refused")) to R.string.skin_retouch_unreachable,
        )

        table.forEach { (error, message) ->
            provider.failNext(error)
            viewModel.skin.preview()
            assertEquals(message, viewModel.uiState.value.skin.message)
            assertEquals(60, viewModel.uiState.value.skin.strengths[SkinRetouchKind.Blemish])
            assertTrue(viewModel.uiState.value.skin.canPreview)
        }
        viewModel.skin.preview()
        assertTrue(viewModel.uiState.value.skin.canApply)
    }

    @Test
    fun `an unconfigured server is a settings path, not a request`() = runTest {
        provider.setAvailability(
            Availability.Unavailable(
                AppError.Invalid(com.diffuse.core.ai.retouch.server.RetouchServerSkinRetouchProvider.NO_SERVER),
            ),
        )
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)

        val skin = viewModel.uiState.value.skin
        assertEquals(SkinServerStatus.NeedsSettings, skin.server)
        assertTrue(skin.needsSettings)
        assertFalse(skin.canPreview)
        viewModel.skin.preview()
        assertEquals(0, provider.prepareCount)
    }

    // ---- apply, undo, redo --------------------------------------------------------------------

    @Test
    fun `적용 is one history entry under the adjustments, and undo and redo call nothing`() = runTest {
        val viewModel = viewModel()
        viewModel.onAdjust(AdjustKind.Sharpen, 0.5f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.SkinRetouch)
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()

        viewModel.applySheet()

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        val operations = state.document!!.operations
        val retouch = operations.filterIsInstance<Operation.SkinRetouch>().single()
        val retouchAt = operations.indexOf(retouch)
        assertTrue(operations[retouchAt - 1] is Operation.Mask)
        assertEquals(retouchAt + 1, operations.indexOfFirst { it is Operation.Adjust })
        assertEquals(0.5f, retouch.settings.strengths[RetouchKind.Blemish])
        assertEquals(0f, retouch.settings.strengths[RetouchKind.Shine])
        assertTrue(retouch.settings.isValid)
        assertNull(state.document.activeMaskId)
        assertEquals(1, repository.retouchSaves)
        assertTrue(renderer.transients.isEmpty())

        viewModel.undo()
        val undone = viewModel.uiState.value.document!!.operations
        assertTrue(undone.none { it is Operation.SkinRetouch || it is Operation.Mask })
        viewModel.redo()
        assertEquals(retouch, viewModel.uiState.value.document!!.skinRetouches().single())
        assertEquals(1, provider.prepareCount)
    }

    @Test
    fun `cancel back and dismiss leave the document, history and files untouched`() = runTest {
        val viewModel = openSheet()
        val before = viewModel.uiState.value.document
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()

        viewModel.cancelSheet()

        assertEquals(before, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.canUndo)
        assertNull(viewModel.skin.draftDocument)
        assertTrue(renderer.transients.isEmpty())
        assertEquals(0, repository.retouchSaves)
        assertEquals(SkinAnalysis.None, viewModel.uiState.value.skin.analysis)
    }

    @Test
    fun `a save failure keeps the sheet and the values for an explicit retry`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()
        repository.failNextRetouchSave = true

        viewModel.applySheet()

        val state = viewModel.uiState.value
        assertEquals(Tool.SkinRetouch, state.selectedTool)
        assertEquals(R.string.skin_retouch_save_failed, state.skin.message)
        assertFalse(state.canUndo)
        assertTrue(state.skin.canApply)
        viewModel.applySheet()
        assertTrue(viewModel.uiState.value.canUndo)
        assertEquals(1, provider.prepareCount)
    }

    // ---- races --------------------------------------------------------------------------------

    @Test
    fun `an answer that arrives after 취소 writes nothing`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        val gate = CompletableDeferred<Unit>()
        provider.gates += gate
        viewModel.skin.preview()
        assertTrue(viewModel.uiState.value.skin.preparing)

        viewModel.cancelSheet()
        gate.complete(Unit)

        assertNull(viewModel.skin.draftDocument)
        assertFalse(viewModel.uiState.value.skin.preparing)
        assertTrue(viewModel.uiState.value.skin.outcomes.isEmpty())
        assertFalse(viewModel.uiState.value.canUndo)
    }

    @Test
    fun `an old request's finally never clears a newer request's busy flag`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        provider.gates += first
        provider.gates += second
        viewModel.skin.preview()
        viewModel.skin.cancelWork()
        viewModel.skin.preview()
        assertTrue(viewModel.uiState.value.skin.preparing)

        first.complete(Unit)

        assertTrue(viewModel.uiState.value.skin.preparing)
        assertTrue(viewModel.uiState.value.skin.outcomes.isEmpty())
        second.complete(Unit)
        assertFalse(viewModel.uiState.value.skin.preparing)
        assertTrue(viewModel.uiState.value.skin.canApply)
    }

    @Test
    fun `a settings save cancels the request and drops its late answer`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        val gate = CompletableDeferred<Unit>()
        provider.gates += gate
        viewModel.skin.preview()

        retouchSettings.update("http://other.example:8094", "t")
        gate.complete(Unit)

        val skin = viewModel.uiState.value.skin
        assertFalse(skin.preparing)
        assertTrue(skin.outcomes.isEmpty())
        assertTrue(skin.needsPreview)
        assertNull(viewModel.skin.draftDocument)
    }

    @Test
    fun `a settings save during face analysis leaves the local analysis running`() = runTest {
        listOf<(CompletableDeferred<Unit>) -> Unit>(
            { analyzer.analyzeGate = it },
            { analyzer.detailGate = it },
        ).forEach { hold ->
            val gate = CompletableDeferred<Unit>()
            hold(gate)
            val viewModel = viewModel()
            viewModel.onToolClick(Tool.SkinRetouch)
            assertEquals(SkinAnalysis.Analyzing, viewModel.uiState.value.skin.analysis)

            retouchSettings.update("http://other.example:8094", "t")
            gate.complete(Unit)

            assertEquals(SkinAnalysis.Ready, viewModel.uiState.value.skin.analysis)
            viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
            viewModel.skin.preview()
            assertTrue(viewModel.uiState.value.skin.canApply)
            viewModel.cancelSheet()
        }
    }

    @Test
    fun `a composite cancelled on its way back to main leaves no transient behind`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val work = QueuedWork()
        fun settle() {
            do runCurrent() while (work.runAll())
        }
        val viewModel = viewModel(work)
        settle()
        viewModel.onToolClick(Tool.SkinRetouch)
        settle()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()
        settle()
        assertNotNull(viewModel.skin.draftDocument)

        // A slider change lands after the composite finished but before its job is back on main.
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 25)
        runCurrent()
        assertTrue(work.runAll())
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 75)
        settle()
        val draft = viewModel.skin.draftDocument!!.operations.filterIsInstance<Operation.SkinRetouch>().single()
        assertEquals(2, renderer.transients.size)
        assertTrue(draft.resultRef in renderer.transients)

        // A settings save in the same window.
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 60)
        runCurrent()
        assertTrue(work.runAll())
        retouchSettings.update("http://other.example:8094", "t")
        settle()
        assertNull(viewModel.skin.draftDocument)
        assertTrue(renderer.transients.isEmpty())

        // And closing the sheet.
        viewModel.skin.preview()
        settle()
        assertTrue(renderer.transients.isNotEmpty())
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 100)
        runCurrent()
        assertTrue(work.runAll())
        viewModel.cancelSheet()
        settle()
        assertNull(viewModel.skin.draftDocument)
        assertTrue(renderer.transients.isEmpty())
    }

    @Test
    fun `undo under the open sheet ends the session and a late answer stays out`() = runTest {
        val viewModel = viewModel()
        viewModel.onAdjust(AdjustKind.Sharpen, 0.5f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.SkinRetouch)
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        val gate = CompletableDeferred<Unit>()
        provider.gates += gate
        viewModel.skin.preview()

        viewModel.undo()
        gate.complete(Unit)

        val state = viewModel.uiState.value
        assertNull(state.selectedTool)
        assertEquals(emptyList<Operation>(), state.document?.operations)
        assertNull(viewModel.skin.draftDocument)
        assertEquals(0, repository.retouchSaves)
    }

    @Test
    fun `a document change while the result is being saved discards the files instead of committing`() = runTest {
        val viewModel = viewModel()
        viewModel.onAdjust(AdjustKind.Sharpen, 0.5f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.SkinRetouch)
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()
        val saveGate = CompletableDeferred<Unit>()
        repository.saveGate = saveGate

        viewModel.applySheet()
        viewModel.undo()
        saveGate.complete(Unit)

        assertEquals(1, repository.retouchSaves)
        assertEquals(1, repository.retouchDiscards)
        assertTrue(viewModel.uiState.value.document!!.operations.none { it is Operation.SkinRetouch })
    }

    @Test
    fun `apply and strength changes are locked while a request is running`() = runTest {
        val viewModel = openSheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 50)
        viewModel.skin.preview()
        viewModel.skin.setStrength(SkinRetouchKind.Shine, 50)
        val gate = CompletableDeferred<Unit>()
        provider.gates += gate
        viewModel.skin.preview()

        viewModel.applySheet()
        viewModel.skin.setStrength(SkinRetouchKind.Blemish, 100)

        assertEquals(50, viewModel.uiState.value.skin.strengths[SkinRetouchKind.Blemish])
        assertFalse(viewModel.uiState.value.canUndo)
        gate.complete(Unit)
        assertTrue(viewModel.uiState.value.skin.canApply)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun openSheet(): EditorViewModel = viewModel().also { it.onToolClick(Tool.SkinRetouch) }

    private fun assertDraft(viewModel: EditorViewModel): EditDocument {
        val draft = viewModel.skin.draftDocument
        assertNotNull(draft)
        assertTrue(draft!!.operations.any { it is Operation.SkinRetouch })
        assertEquals(draft, renderer.lastPreviewed)
        return draft
    }

    private fun viewModel(dispatchers: DispatcherProvider = TestDispatchers) = EditorViewModel(
        context = context,
        repository = repository,
        renderer = renderer,
        ai = EditorAi(
            FakeSegmentationProvider(openDelayMs = 0),
            FakeEraseProvider(),
            FakeFillProvider(),
            FakeOutpaintProvider(),
            FakePlanProvider(),
            FakeSpeechInput(),
            Sam3Settings(context),
            GeminiSettings(context),
            MonetSettings(context),
            FakeAutoEnhanceProvider(),
            FakeMatchStyleProvider(),
            FakePortraitDetector(PortraitResult.Portrait),
            provider,
            analyzer,
            retouchSettings,
        ),
        dispatchers = dispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    /** The work dispatcher, run only when a test says so: a composite can finish while its job waits for main. */
    private class QueuedWork : CoroutineDispatcher(), DispatcherProvider {
        private val pending = ArrayDeque<Runnable>()
        override val default: CoroutineDispatcher get() = this
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending += block
        }

        /** True when anything ran. */
        fun runAll(): Boolean {
            val ran = pending.isNotEmpty()
            while (pending.isNotEmpty()) pending.removeFirst().run()
            return ran
        }
    }

    private class RecordingRenderer : Renderer {
        val transients = mutableMapOf<ImageRef, Bitmap>()
        var lastPreviewed: EditDocument? = null

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap> {
            lastPreviewed = document
            return Result.Success(Bitmap.createBitmap(PREVIEW, PREVIEW, Bitmap.Config.ARGB_8888))
        }

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit): Result<Bitmap> =
            Result.Success(
                Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888).apply { eraseColor(SKIN) },
            )

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) {
            transients[ref] = bitmap
        }

        override fun removeTransient(ref: ImageRef) {
            transients.remove(ref)
        }

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        var retouchSaves = 0
        var retouchDiscards = 0
        var failNextRetouchSave = false
        var saveGate: CompletableDeferred<Unit>? = null

        private var document =
            EditDocument(id = PROJECT_ID, source = ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success(PROJECT_ID)
        override suspend fun load(id: String): Result<EditDocument> = Result.Success(document)
        override suspend fun save(document: EditDocument): Result<Unit> {
            this.document = document
            return Result.Success(Unit)
        }

        override suspend fun saveMask(projectId: String, maskId: String, alpha: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(projectId: String, eraseId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(projectId: String, fillId: String, bitmap: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(
            projectId: String,
            outpaintId: String,
            bitmap: Bitmap,
        ): Result<ImageRef> =
            Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun saveSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
            result: Bitmap,
            support: Bitmap,
        ): Result<SkinRetouchFiles> {
            retouchSaves++
            assertEquals(CANVAS, result.width)
            assertEquals(CANVAS, support.width)
            saveGate?.let {
                saveGate = null
                it.await()
            }
            if (failNextRetouchSave) {
                failNextRetouchSave = false
                return Result.Failure(AppError.Io(IOException("disk full")))
            }
            return Result.Success(
                SkinRetouchFiles(ImageRef("/p/retouch_$retouchId.png"), ImageRef("/p/mask_$maskId.png")),
            )
        }

        override suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String): Result<Unit> {
            retouchDiscards++
            return Result.Success(Unit)
        }

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap): Result<ImageRef> =
            Result.Success(ImageRef("/p/shot_$fileId.png"))

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>): Result<Unit> =
            Result.Success(Unit)

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val CANVAS = 600
        const val PREVIEW = 64
        const val SKIN = 0xFFC89A7C.toInt()
        val FACE = Rect(150, 150, 450, 450)
    }
}
