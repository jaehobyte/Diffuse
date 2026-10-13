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
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.CurrentAdjust
import com.diffuse.core.ai.PlanContext
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.PlanOutcome
import com.diffuse.core.ai.PlanProposal
import com.diffuse.core.ai.PlanRequest
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

/**
 * The 지시 ViewModel harness `DirectToolTest` and `DirectRecommendTest` share: the fakes, the
 * colour-coded renderer and the in-memory repository, so both build the screen the same way.
 */
internal class DirectHarness {
    val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    val eraser = FakeEraseProvider()
    val filler = FakeFillProvider()
    val planner = FakePlanProvider()
    val renderer = DirectFakeRenderer()
    val repository = DirectRecordingRepository()
    val settings = Sam3Settings(ApplicationProvider.getApplicationContext()).apply {
        update("http://localhost:8080", "token")
    }
    val geminiSettings = GeminiSettings(ApplicationProvider.getApplicationContext()).apply { update("test-key") }
    private val monetSettings = MonetSettings(ApplicationProvider.getApplicationContext())

    fun opened(): EditorViewModel = viewModel().also { it.onToolClick(Tool.Direct) }

    fun viewModel() = EditorViewModel(
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
        ),
        dispatchers = TestDispatchers,
        savedStateHandle = SavedStateHandle(mapOf(EditorViewModel.PROJECT_ID to PROJECT_ID)),
    )
}

/** Resolves any mask to a full-frame one, as the erase tool's test does. */
/** T70: colour-coded by whether the document still carries adjustments, as the tool tests are. */
internal class DirectFakeRenderer : Renderer {
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

    companion object {
        const val SIZE = 32
        val PLAIN = android.graphics.Color.rgb(40, 50, 60)
        val ADJUSTED = android.graphics.Color.rgb(200, 210, 220)
        private const val OPAQUE = 255
        private const val ALPHA_SHIFT = 24
    }
}

internal class DirectRecordingRepository : ProjectRepository {
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

private const val PROJECT_ID = "p"
