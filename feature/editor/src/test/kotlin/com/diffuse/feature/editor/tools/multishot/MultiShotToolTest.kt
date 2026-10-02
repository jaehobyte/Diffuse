package com.diffuse.feature.editor.tools.multishot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.monet.MonetSettings
import com.diffuse.core.ai.retouch.server.RetouchServerSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.FakeSpeechInput
import com.diffuse.core.common.Result
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.data.SkinRetouchFiles
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.Operation
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.editor.EditorAi
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.editor.StripItem
import com.diffuse.feature.editor.TestDispatchers
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolGroup
import com.diffuse.feature.editor.ToolMenuProfile
import com.diffuse.feature.editor.stripItems
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * specs/multishot.md §2, §8, §9 requirement 27. 멀티샷 through the real `EditorViewModel`: the
 * photo read by the production `ImageLoader`, SAM 3 by the shared fake, history by the real stack.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiShotToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val renderer = RecordingRenderer()
    private val repository = RecordingRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the tool is at the AI level of both menus`() {
        ToolMenuProfile.entries.forEach { profile ->
            assertTrue(StripItem.OfTool(Tool.MultiShot) in stripItems(ToolGroup.Ai, profile))
            assertFalse(StripItem.OfTool(Tool.MultiShot) in stripItems(ToolGroup.Root, profile))
        }
    }

    @Test
    fun `entering sends nothing and 취소 leaves the document and history alone`() = runTest {
        val viewModel = viewModel()
        val before = viewModel.uiState.value.document

        viewModel.onToolClick(Tool.MultiShot)
        viewModel.multiShot.setMode(MultiShotMode.Free)
        viewModel.multiShot.onPhotoPicked(photo(), null)

        assertEquals(Tool.MultiShot, viewModel.uiState.value.selectedTool)
        assertEquals(ShotStatus.Picked, viewModel.uiState.value.multiShot.items.single().status)
        assertEquals(0, segmentation.openCount)

        viewModel.cancelSheet()

        assertEquals(before, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.canUndo)
        assertFalse(viewModel.uiState.value.multiShot.open)
    }

    @Test
    fun `적용 is one history step, and undo and redo move exactly it`() = runTest {
        val viewModel = viewModel()
        val entry = viewModel.uiState.value.document!!

        viewModel.onToolClick(Tool.MultiShot)
        extractOne(viewModel)
        viewModel.applySheet()

        val applied = viewModel.uiState.value.document!!
        // REVIEW N1: the buttons follow the history the moment the step lands.
        assertTrue(viewModel.uiState.value.canUndo)
        assertFalse(viewModel.uiState.value.canRedo)
        assertNull(viewModel.uiState.value.selectedTool)
        assertEquals(entry.operations, applied.operations.dropLast(1))
        assertEquals("the selection is kept", entry.activeMaskId, applied.activeMaskId)
        assertEquals(1, repository.shotSaves)

        viewModel.undo()
        assertEquals(entry, viewModel.uiState.value.document)
        assertFalse(viewModel.uiState.value.canUndo)
        assertTrue(viewModel.uiState.value.canRedo)
        // One step, not two: a second undo has nothing left of the composite to take back.
        viewModel.undo()
        assertEquals(entry, viewModel.uiState.value.document)
        viewModel.redo()
        assertEquals(applied, viewModel.uiState.value.document)
    }

    @Test
    fun `while open the canvas shows the draft on the un-cropped canvas`() = runTest {
        repository.document = repository.document.withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)
        val viewModel = viewModel()

        viewModel.onToolClick(Tool.MultiShot)
        extractOne(viewModel)

        val shown = renderer.lastPreviewed!!
        assertTrue(shown.operations.any { it is Operation.MultiShot })
        assertTrue(shown.operations.none { it is Operation.Crop })

        viewModel.cancelSheet()
        assertTrue(renderer.lastPreviewed!!.operations.any { it is Operation.Crop })
        assertTrue(renderer.lastPreviewed!!.operations.none { it is Operation.MultiShot })
    }

    @Test
    fun `the selection tool's session is released before the photo is sent, and ours on 적용`() = runTest {
        val viewModel = viewModel()
        viewModel.onToolClick(Tool.Select)
        viewModel.cancelSheet()
        assertEquals(1, segmentation.openSessions.size)

        viewModel.onToolClick(Tool.MultiShot)
        extractOne(viewModel)
        viewModel.applySheet()

        assertEquals(2, segmentation.openCount)
        assertTrue(segmentation.openSessions.isEmpty())
    }

    @Test
    fun `undo under an open sheet ends the session`() = runTest {
        val viewModel = viewModel()
        viewModel.onAdjust(AdjustKind.Exposure, 0.2f)
        viewModel.onAdjustFinished()
        viewModel.onToolClick(Tool.MultiShot)
        extractOne(viewModel)

        viewModel.undo()

        assertNull(viewModel.uiState.value.selectedTool)
        assertFalse(viewModel.uiState.value.multiShot.open)
        assertEquals(1, repository.shotDiscards)
    }

    @Test
    fun `the time layout's one press extracts the hero and every picked photo, lays them out, and 적용 is one step`() =
        runTest {
            val viewModel = viewModel()
            val entry = viewModel.uiState.value.document!!
            viewModel.onToolClick(Tool.MultiShot)
            viewModel.multiShot.setMode(MultiShotMode.Timeline)
            val request = viewModel.multiShot.requestPick(null)!!
            viewModel.multiShot.onPicked(request.id, listOf(photo(), photo()))
            assertEquals(2, viewModel.uiState.value.multiShot.items.size)
            assertEquals("picking sends nothing", 0, segmentation.openCount)

            viewModel.multiShot.runAll()
            // The shared fake answers "person" with two people: each photo waits for the choice
            // and the run goes on by itself once it is finished — no other press in between.
            var choices = 0
            while (viewModel.uiState.value.multiShot.run != null) {
                assertEquals(RunPhase.Choosing, viewModel.uiState.value.multiShot.run?.phase)
                assertEquals("one photo sent at a time", choices + 1, segmentation.openCount)
                viewModel.multiShot.chooseCandidate(0)
                viewModel.multiShot.finishExtraction()
                choices++
            }

            val state = viewModel.uiState.value.multiShot
            assertEquals("the hero and both photos", 3, choices)
            assertTrue(state.positionsCurrent)
            assertTrue(state.canApply)
            assertTrue("every session was closed", segmentation.openSessions.isEmpty())
            val hero = state.heroAnchor!!
            val (a, b) = state.timeOrder
            val anchors = state.draftShots.associate {
                it.id to com.diffuse.core.imaging.render.MultiShotLayout.onCanvas(
                    it, it.anchor!!, state.canvasWidth, state.canvasHeight,
                )
            }
            assertEquals(hero.x - 1f / 3, anchors.getValue(a).x, 1e-4f)
            assertEquals(hero.x + 1f / 3, anchors.getValue(b).x, 1e-4f)
            assertEquals(hero.y, anchors.getValue(a).y, 1e-4f)

            viewModel.applySheet()

            val applied = viewModel.uiState.value.document!!
            val composite = applied.multiShot()!!
            assertEquals(MultiShotMode.Timeline, composite.mode)
            assertEquals(state.draftShots, composite.shots)
            assertEquals(entry.operations, applied.operations.dropLast(1))
            assertEquals(3, repository.shotSaves)
            viewModel.undo()
            assertEquals(entry, viewModel.uiState.value.document)
            viewModel.redo()
            assertEquals(applied, viewModel.uiState.value.document)
        }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun extractOne(viewModel: EditorViewModel) {
        if (viewModel.uiState.value.multiShot.mode == null) viewModel.multiShot.setMode(MultiShotMode.Free)
        viewModel.multiShot.onPhotoPicked(photo(), null)
        viewModel.multiShot.extract()
        // The fake answers "person" with two people; the user picks one.
        viewModel.multiShot.chooseCandidate(0)
        viewModel.multiShot.finishExtraction()
        assertEquals(ShotStatus.Ready, viewModel.uiState.value.multiShot.selected?.status)
    }

    private fun photo(): Uri {
        val file = File(temp.root, "golf.png")
        val bitmap = Bitmap.createBitmap(PHOTO_W, PHOTO_H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun viewModel() = EditorViewModel(
        context = context,
        repository = repository,
        renderer = renderer,
        ai = EditorAi(
            segmentation,
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
            FakePortraitDetector(PortraitResult.NotPortrait),
            FakeSkinRetouchProvider(),
            FakeFaceRegionAnalyzer(),
            RetouchServerSettings(context),
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )

    private class RecordingRenderer : Renderer {
        var lastPreviewed: EditDocument? = null

        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int): Result<Bitmap> {
            lastPreviewed = document
            return Result.Success(Bitmap.createBitmap(PREVIEW, PREVIEW, Bitmap.Config.ARGB_8888))
        }

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit): Result<Bitmap> =
            Result.Success(Bitmap.createBitmap(PREVIEW, PREVIEW, Bitmap.Config.ARGB_8888))

        override fun putTransient(ref: ImageRef, bitmap: Bitmap) = Unit

        override fun removeTransient(ref: ImageRef) = Unit

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingRepository : ProjectRepository {
        var shotSaves = 0
        var shotDiscards = 0
        var document = EditDocument(id = PROJECT_ID, source = ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
            .withMask(ImageRef("/p/mask_m.png"), "m")

        override fun observeAll(): Flow<List<ProjectSummary>> = flowOf(emptyList())
        override suspend fun create(source: SourceImage): Result<String> = Result.Success(PROJECT_ID)
        override suspend fun load(id: String): Result<EditDocument> = Result.Success(document)
        override suspend fun save(document: EditDocument): Result<Unit> = Result.Success(Unit)

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
        ): Result<ImageRef> = Result.Success(ImageRef("/p/outpaint_$outpaintId.png"))

        override suspend fun saveSkinRetouch(
            projectId: String,
            retouchId: String,
            maskId: String,
            result: Bitmap,
            support: Bitmap,
        ): Result<SkinRetouchFiles> =
            Result.Success(SkinRetouchFiles(ImageRef("/p/retouch_$retouchId.png"), ImageRef("/p/mask_$maskId.png")))

        override suspend fun discardSkinRetouch(projectId: String, retouchId: String, maskId: String): Result<Unit> =
            Result.Success(Unit)

        override suspend fun saveShotSubject(projectId: String, fileId: String, subject: Bitmap): Result<ImageRef> {
            shotSaves++
            // A subject at the photo's working size, or a hero mask at the input canvas's.
            assertTrue(subject.width == PHOTO_W || subject.width == PREVIEW)
            return Result.Success(ImageRef("/p/shot_$fileId.png"))
        }

        override suspend fun discardShotSubjects(projectId: String, fileIds: List<String>): Result<Unit> {
            shotDiscards += fileIds.size
            return Result.Success(Unit)
        }

        override suspend fun duplicate(id: String): Result<String> = Result.Success("copy")
        override suspend fun delete(id: String): Result<Unit> = Result.Success(Unit)
    }

    private companion object {
        const val PROJECT_ID = "p"
        const val PREVIEW = 64
        const val PHOTO_W = 60
        const val PHOTO_H = 40
    }
}
