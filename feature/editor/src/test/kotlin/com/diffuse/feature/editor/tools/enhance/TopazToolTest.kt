package com.diffuse.feature.editor.tools.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeMatchStyleProvider
import com.diffuse.core.ai.FakeOutpaintProvider
import com.diffuse.core.ai.FakePlanProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.FakeTopazProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.data.TopazWorkspace
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * specs/topaz_enhancement.md §8: the shared Topaz tool through the real `EditorViewModel`, with
 * the provider faked. Every "no paid job" claim is a count of [FakeTopazProvider.submitCount].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopazToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var topaz: FakeTopazProvider
    private lateinit var repository: RecordingRepository
    private lateinit var renderer: RecordingRenderer
    private lateinit var workRoot: File
    private val saves = mutableListOf<Saved>()
    private var saveResult: Result<Unit> = Result.Success(Unit)

    private data class Saved(val file: File, val mimeType: String, val width: Int, val height: Int)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        topaz = FakeTopazProvider()
        repository = RecordingRepository()
        renderer = RecordingRenderer()
        workRoot = temp.newFolder("topaz_work")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- nothing is paid for without 실행 ---------------------------------

    @Test
    fun `opening, choosing options and comparing purposes start no job`() = runTest {
        val viewModel = opened()

        viewModel.topaz.selectPurpose(TopazPurpose.Denoise)
        viewModel.topaz.selectPurpose(TopazPurpose.Upscale)
        viewModel.topaz.setScale(4)
        viewModel.topaz.setFaceRecovery(true)
        viewModel.topaz.setComparing(true)

        assertEquals(Tool.Enhance, viewModel.uiState.value.selectedTool)
        assertEquals(0, topaz.submitCount)
        assertEquals(0, topaz.estimateCount)
        assertNull(viewModel.uiState.value.topaz.result)
    }

    @Test
    fun `the input is the committed document rendered in full, crop and colour included`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Color)
        viewModel.onAdjust(AdjustKind.Saturation, 0.4f)
        viewModel.applySheet()
        viewModel.onToolClick(Tool.Crop)
        viewModel.applySheet()

        viewModel.onToolClick(Tool.Enhance)

        val sent = renderer.fullDocuments.last()
        assertEquals(viewModel.uiState.value.document, sent)
        assertTrue(sent.operations.isNotEmpty())
        val input = viewModel.uiState.value.topaz.input!!
        assertEquals(FULL_WIDTH, input.width)
        assertEquals(FULL_HEIGHT, input.height)
    }

    @Test
    fun `the estimate is fetched only when asked, and an option change drops it`() = runTest {
        val viewModel = opened()

        viewModel.topaz.checkEstimate()
        assertEquals(TopazEstimateState.Known(3), viewModel.uiState.value.topaz.estimate)
        viewModel.topaz.setScale(4)

        assertEquals(TopazEstimateState.NotChecked, viewModel.uiState.value.topaz.estimate)
        assertEquals(1, topaz.estimateCount)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `an estimate that fails is unknown, never zero`() = runTest {
        topaz.estimateFailure = TopazFailure.Server
        val viewModel = opened()

        viewModel.topaz.checkEstimate()

        assertEquals(TopazEstimateState.Unknown, viewModel.uiState.value.topaz.estimate)
    }

    // ---- one run, one job ------------------------------------------------

    @Test
    fun `run submits once, and a second tap while it runs adds nothing`() = runTest {
        val gate = CompletableDeferred<Unit>().also { topaz.gate = it }
        val viewModel = opened()

        viewModel.topaz.run()
        viewModel.topaz.run()
        assertTrue(viewModel.uiState.value.topaz.working)
        gate.complete(Unit)

        assertEquals(1, topaz.submitCount)
        val result = viewModel.uiState.value.topaz.result!!
        assertEquals(FULL_WIDTH * 2, result.width)
        assertEquals(FULL_HEIGHT * 2, result.height)
        assertFalse(viewModel.uiState.value.topaz.working)
    }

    @Test
    fun `the result is shown at the input's preview size, and holding shows the input`() = runTest {
        val viewModel = ranOnce()
        val state = viewModel.uiState.value.topaz

        val after = state.result!!.after.asAndroidBitmap()
        val before = state.input!!.before.asAndroidBitmap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertEquals(FakeTopazProvider.RESULT_COLOR, after.getPixel(after.width / 2, after.height / 2).opaqueish())

        viewModel.topaz.setComparing(true)
        assertTrue(viewModel.uiState.value.topaz.comparing)
    }

    @Test
    fun `the original document and its history are untouched by a result`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Light)
        viewModel.onAdjust(AdjustKind.Exposure, 0.3f)
        viewModel.applySheet()
        val before = viewModel.uiState.value.document
        viewModel.onToolClick(Tool.Enhance)

        viewModel.topaz.run()

        assertEquals(before, viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.canUndo)
        viewModel.cancelSheet()
        assertEquals(before, viewModel.uiState.value.document)
    }

    @Test
    fun `a failure is reported with the reason, and leaves a retry that is a new job`() = runTest {
        topaz.nextFailure = TopazFailure.InsufficientCredits
        val viewModel = opened()

        viewModel.topaz.run()

        val state = viewModel.uiState.value.topaz
        assertEquals(TopazFailure.InsufficientCredits, state.failure)
        assertEquals(R.string.topaz_error_credits, state.message)
        assertNull(state.result)
        viewModel.topaz.run()
        assertEquals(2, topaz.submitCount)
        assertNotNull(viewModel.uiState.value.topaz.result)
    }

    // ---- cancel, close, document and key changes -------------------------

    @Test
    fun `cancelling a run cancels the job and keeps nothing from it`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = opened()
        viewModel.topaz.run()

        viewModel.topaz.cancelWork()

        assertEquals(1, topaz.cancelledCount)
        assertFalse(viewModel.uiState.value.topaz.working)
        assertNull(viewModel.uiState.value.topaz.result)
        assertEquals(R.string.topaz_cancelled, viewModel.uiState.value.topaz.message)
    }

    @Test
    fun `run B after cancelling A carries only B`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = opened()
        viewModel.topaz.run()
        viewModel.topaz.cancelWork()

        topaz.gate = null
        viewModel.topaz.selectPurpose(TopazPurpose.Denoise)
        viewModel.topaz.run()

        val result = viewModel.uiState.value.topaz.result!!
        assertEquals(TopazPurpose.Denoise, result.purpose)
        assertEquals(FULL_WIDTH, result.width)
        assertEquals(2, topaz.submitCount)
        assertEquals(1, sessionDirs().single().listFiles { f -> f.name.startsWith("output_") }!!.size)
    }

    @Test
    fun `an undo under the sheet abandons the run and rebuilds the input`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Light)
        viewModel.onAdjust(AdjustKind.Exposure, 0.3f)
        viewModel.applySheet()
        viewModel.onToolClick(Tool.Enhance)
        val gate = CompletableDeferred<Unit>().also { topaz.gate = it }
        viewModel.topaz.run()

        viewModel.undo()
        gate.complete(Unit)

        val state = viewModel.uiState.value.topaz
        assertNull(state.result)
        assertEquals(R.string.topaz_document_changed, state.message)
        assertEquals(viewModel.uiState.value.document, renderer.fullDocuments.last())
        assertEquals(1, topaz.cancelledCount)
    }

    @Test
    fun `a key change abandons the run in flight`() = runTest {
        topaz.gate = CompletableDeferred()
        val viewModel = opened()
        viewModel.topaz.run()

        topaz.setKey("another-key")

        assertFalse(viewModel.uiState.value.topaz.working)
        assertEquals(R.string.topaz_key_changed, viewModel.uiState.value.topaz.message)
        assertEquals(1, topaz.cancelledCount)
    }

    @Test
    fun `closing the sheet removes its files and a late answer changes nothing`() = runTest {
        val viewModel = ranOnce()
        assertTrue(sessionDirs().isNotEmpty())

        viewModel.cancelSheet()

        assertNull(viewModel.uiState.value.selectedTool)
        assertFalse(viewModel.uiState.value.topaz.isOpen)
        assertTrue(sessionDirs().isEmpty())
    }

    @Test
    fun `without a key the sheet explains itself and 실행 stays off`() = runTest {
        topaz.setKey("")
        val viewModel = opened()

        viewModel.topaz.run()

        val state = viewModel.uiState.value.topaz
        assertEquals(Tool.Enhance, viewModel.uiState.value.selectedTool)
        assertEquals(R.string.topaz_needs_key, state.blocker)
        assertFalse(state.canRun)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `a transparent document is refused before anything is sent`() = runTest {
        repository.hasAlpha = true
        val viewModel = opened()

        viewModel.topaz.run()

        assertEquals(R.string.topaz_blocked_alpha, viewModel.uiState.value.topaz.blocker)
        assertEquals(0, topaz.submitCount)
    }

    // ---- saving and the new edit copy ------------------------------------

    @Test
    fun `고해상도 저장 hands over the result file itself, and a retry submits nothing`() = runTest {
        saveResult = Result.Failure(AppError.Io(java.io.IOException("full")))
        val viewModel = ranOnce(TopazOptions(scale = 4))

        viewModel.topaz.save()
        assertEquals(R.string.topaz_save_failed, viewModel.uiState.value.topaz.message)
        saveResult = Result.Success(Unit)
        viewModel.topaz.save()

        assertEquals(2, saves.size)
        val result = viewModel.uiState.value.topaz.result!!
        assertEquals(Saved(result.file, "image/jpeg", FULL_WIDTH * 4, FULL_HEIGHT * 4), saves.last())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.file.path, bounds)
        assertEquals(FULL_WIDTH * 4, bounds.outWidth)
        assertTrue(viewModel.uiState.value.topaz.saved)
        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `a new edit copy is created once from the result, at the working size, without the old ops`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Light)
        viewModel.onAdjust(AdjustKind.Exposure, 0.3f)
        viewModel.applySheet()
        val original = viewModel.uiState.value.document
        viewModel.onToolClick(Tool.Enhance)
        viewModel.topaz.setScale(4)
        assertTrue(viewModel.uiState.value.topaz.reopensSmaller)
        viewModel.topaz.run()

        viewModel.topaz.openCopy()
        viewModel.topaz.openCopy()

        val created = repository.created.single()
        assertEquals(4096, maxOf(created.widthPx, created.heightPx))
        assertEquals(FULL_WIDTH * 4, created.sourceWidthPx)
        assertEquals("copy-1", viewModel.uiState.value.topaz.openedProjectId)

        viewModel.topaz.onNavigated()
        assertNull(viewModel.uiState.value.topaz.openedProjectId)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(original, viewModel.uiState.value.document)
        assertTrue(viewModel.uiState.value.canUndo)
        assertEquals(1, topaz.submitCount)
    }

    @Test
    fun `a failed copy keeps the editor where it was and navigates nowhere`() = runTest {
        repository.failCreate = true
        val viewModel = ranOnce()

        viewModel.topaz.openCopy()

        assertNull(viewModel.uiState.value.topaz.openedProjectId)
        assertEquals(R.string.topaz_open_failed, viewModel.uiState.value.topaz.message)
        assertEquals(Tool.Enhance, viewModel.uiState.value.selectedTool)
        assertNotNull(viewModel.uiState.value.topaz.result)
    }

    // ---- shortcuts --------------------------------------------------------

    @Test
    fun `a shortcut from a sheet with unapplied values is refused and keeps them`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Light)
        viewModel.onAdjust(AdjustKind.Exposure, 0.3f)

        viewModel.openTopaz(TopazGroup.Light)

        assertEquals(Tool.Light, viewModel.uiState.value.selectedTool)
        assertEquals(0.3f, viewModel.uiState.value.document!!.adjustValue(AdjustKind.Exposure), 0f)
        assertEquals(R.string.topaz_shortcut_blocked, viewModel.uiState.value.topaz.message)
        assertTrue(renderer.fullDocuments.isEmpty())
    }

    @Test
    fun `a shortcut from an untouched sheet opens the shared sheet on its purpose`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Color)

        viewModel.openTopaz(TopazGroup.Color)

        assertEquals(Tool.Enhance, viewModel.uiState.value.selectedTool)
        assertEquals(TopazGroup.Color, viewModel.uiState.value.topaz.group)
        assertEquals(TopazPurpose.ColorBalance, viewModel.uiState.value.topaz.purpose)
        assertEquals(0, topaz.submitCount)
    }

    @Test
    fun `the detail shortcut opens 화질 개선, and the lighting purpose answers at the input size`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Detail)
        viewModel.openTopaz(TopazGroup.Enhance)
        assertEquals(TopazGroup.Enhance, viewModel.uiState.value.topaz.group)
        viewModel.cancelSheet()

        viewModel.onToolClick(Tool.Light)
        viewModel.openTopaz(TopazGroup.Light)
        viewModel.topaz.run()

        assertEquals(TopazPurpose.Lighting, topaz.lastJob!!.purpose)
        assertEquals(FULL_WIDTH, viewModel.uiState.value.topaz.result!!.width)
    }

    @Test
    fun `changing options after a result marks it as the previous settings' result`() = runTest {
        val viewModel = ranOnce()

        viewModel.topaz.setScale(4)

        assertTrue(viewModel.uiState.value.topaz.stale)
        assertTrue(viewModel.uiState.value.topaz.canRun)
        assertEquals(1, topaz.submitCount)
    }

    // ---- fixtures ---------------------------------------------------------

    private fun opened(): EditorViewModel = viewModel().also { it.onToolClick(Tool.Enhance) }

    private fun ranOnce(options: TopazOptions = TopazOptions(scale = 2)): EditorViewModel = opened().also {
        it.topaz.setScale(options.scale)
        it.topaz.run()
        assertNotNull("the run produced no result", it.uiState.value.topaz.result)
    }

    private fun sessionDirs(): List<File> = workRoot.listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun Int.opaqueish(): Int = this or (0xFF shl 24)

    private fun viewModel(): EditorViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return EditorViewModel(
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
                FakeMatchStyleProvider(),
                topaz,
                testTopazSettings(),
            ),
            dispatchers = TestDispatchers,
            savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
            topazStorage = TopazStorage(TopazWorkspace(workRoot, TestDispatchers)) { file, mime, width, height ->
                saves += Saved(file, mime, width, height)
                saveResult
            },
        )
    }

    private class RecordingRenderer : Renderer {
        val fullDocuments = mutableListOf<EditDocument>()

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit): Result<Bitmap> {
            fullDocuments += document
            return Result.Success(Bitmap.createBitmap(FULL_WIDTH, FULL_HEIGHT, Bitmap.Config.ARGB_8888))
        }

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit
        override fun removeTransient(ref: ImageRef) = Unit
        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        val created = mutableListOf<SourceImage>()
        var failCreate = false
        var hasAlpha = false
        private var document = EditDocument(PROJECT_ID, ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> {
            if (failCreate) return Result.Failure(AppError.Io(java.io.IOException("full")))
            created += source
            return Result.Success("copy-${created.size}")
        }

        /** A `.png` source is how a document says it has alpha (edit_model.md). */
        override suspend fun load(id: String): Result<EditDocument> =
            Result.Success(if (hasAlpha) document.copy(source = ImageRef("/p.png")) else document)

        override suspend fun save(document: EditDocument): Result<Unit> {
            this.document = document
            return Result.Success(Unit)
        }

        override suspend fun saveMask(projectId: String, maskId: String, alpha: Bitmap) =
            Result.Success(ImageRef("/p/mask_$maskId.png"))

        override suspend fun saveEraseResult(projectId: String, eraseId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/erase_$eraseId.png"))

        override suspend fun saveFillResult(projectId: String, fillId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/fill_$fillId.png"))

        override suspend fun saveOutpaintResult(projectId: String, outpaintId: String, bitmap: Bitmap) =
            Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun saveSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
            result: Bitmap,
            support: Bitmap,
        ) = Result.Success(SkinRetouchFiles(ImageRef("/p/r.png"), ImageRef("/p/m.png")))

        override suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String) =
            Result.Success(Unit)

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap) =
            Result.Success(ImageRef("/p/shot_$fileId.png"))

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>) = Result.Success(Unit)
        override suspend fun duplicate(id: String): Result<String> = Result.Success("dup")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val FULL_WIDTH = 1200
        const val FULL_HEIGHT = 900
        const val PREVIEW_WIDTH = 120
        const val PREVIEW_HEIGHT = 90
    }
}
