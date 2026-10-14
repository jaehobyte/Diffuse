package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeFillProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.ai.PlanTarget
import com.diffuse.core.ai.StyleId
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.tools.erase.EraseCommit
import com.diffuse.feature.editor.tools.fill.FillCommit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * specs/vibe_edit.md §14: the runner regressions the evaluated workflows lean on, asserted on the
 * documents a run actually commits — values as final settings, a subject's own selection, and a
 * failed selection never turning into a whole-photo edit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanRunnerProposalTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0).apply { textInstances = 1 }

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private val runner = PlanRunner(
        segmentation = segmentation,
        erase = FakeEraseProvider(),
        fill = FakeFillProvider(),
        dispatchers = dispatchers,
        saveMask = { maskId, _ -> Result.Success(ImageRef("/p/mask_$maskId.png")) },
        fillCommit = FillCommit(
            saveMask = { maskId, _ -> Result.Success(ImageRef("/p/mask_$maskId.png")) },
            saveResult = { fillId, _ -> Result.Success(ImageRef("/p/fill_$fillId.png")) },
        ),
        eraseCommit = EraseCommit(
            saveMask = { maskId, _ -> Result.Success(ImageRef("/p/mask_$maskId.png")) },
            saveResult = { eraseId, _ -> Result.Success(ImageRef("/p/erase_$eraseId.png")) },
        ),
        generativeInput = { frame() },
        styleParams = { id, _ -> if (id == StyleId.FilmWarm) STYLE_PARAMS else emptyMap() },
    )

    @Test
    fun `brighter on a raised exposure replaces it with the higher final value`() = runTest {
        val raised = document().withAdjust(AdjustKind.Exposure, RAISED)

        val result = last(EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, BRIGHTER))), raised)

        assertEquals(BRIGHTER, result.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(1, result.operations.filterIsInstance<Operation.Adjust>().size)
    }

    @Test
    fun `a face selection carries a local adjust and leaves the whole-photo value alone`() = runTest {
        val raised = document().withAdjust(AdjustKind.Exposure, RAISED)

        val result = last(
            EditPlan(
                listOf(
                    PlanStep.Select("face", "n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, LOCAL, PlanTarget.New("n1")),
                ),
            ),
            raised,
        )

        val face = result.activeMaskId
        assertEquals(RAISED, result.adjustValue(AdjustKind.Exposure), TOLERANCE)
        assertEquals(LOCAL, result.adjustValue(AdjustKind.Exposure, face), TOLERANCE)
    }

    /** §14: "얼굴만 밝게" uses the face it selected, never the selection the document already had. */
    @Test
    fun `a new subject's adjust lands on its own selection, not the earlier one`() = runTest {
        val earlier = document().withMask(ImageRef("/p/mask_old.png"), id = "old")

        val result = last(
            EditPlan(
                listOf(
                    PlanStep.Select("face", "n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, LOCAL, PlanTarget.New("n1")),
                ),
            ),
            earlier,
        )

        val adjust = result.operations.filterIsInstance<Operation.Adjust>().single()
        assertNotEquals("old", adjust.maskId)
        assertEquals(result.activeMaskId, adjust.maskId)
    }

    /** §14: two green things — the bus is selected and adjusted; no colour range touches the plants. */
    @Test
    fun `one of two same-coloured objects is changed through its selection only`() = runTest {
        val result = last(
            EditPlan(
                listOf(
                    PlanStep.Select("green bus", "n1"),
                    PlanStep.Adjust(AdjustKind.Contrast, LOCAL, PlanTarget.New("n1")),
                ),
            ),
            document(),
        )

        val adjusts = result.operations.filterIsInstance<Operation.Adjust>()
        assertTrue(adjusts.all { it.maskId == result.activeMaskId && it.kind.hsl == null })
    }

    @Test
    fun `a failed selection stops the run and falls back to nothing global`() = runTest {
        val events = runner.run(
            EditPlan(
                listOf(
                    PlanStep.Select(ABSENT, "n1"),
                    PlanStep.Adjust(AdjustKind.Exposure, LOCAL, PlanTarget.New("n1")),
                ),
            ),
            document(),
            frame(),
            activeMask = null,
            sourceAspect = SOURCE_ASPECT,
        ).toList()

        assertEquals(RunEvent.Stopped(0, AppError.Invalid("${PlanRunner.NOT_FOUND_PREFIX}$ABSENT")), events.last())
        assertTrue(events.none { it is RunEvent.Committed })
    }

    /** §14: a style replaces the whole-photo values it carries; it never stacks a second copy. */
    @Test
    fun `a style over existing values replaces them, one op per kind`() = runTest {
        val before = document().withAdjust(AdjustKind.Contrast, RAISED)

        val result = last(EditPlan(listOf(PlanStep.Style(StyleId.FilmWarm, FULL))), before)

        val contrast = result.operations.filterIsInstance<Operation.Adjust>().filter { it.kind == AdjustKind.Contrast }
        assertEquals(1, contrast.size)
        assertEquals(STYLE_PARAMS.getValue(AdjustKind.Contrast), contrast.single().value, TOLERANCE)
    }

    @Test
    fun `a crop stays the last op after a local adjust`() = runTest {
        val result = last(
            EditPlan(
                listOf(
                    PlanStep.Select("flowers", "n1"),
                    PlanStep.Adjust(AdjustKind.Vibrance, LOCAL, PlanTarget.New("n1")),
                    PlanStep.Crop(CropRatio.Portrait3x4),
                ),
            ),
            document(),
        )

        assertTrue(result.operations.last() is Operation.Crop)
    }

    /** §9.3: each committed step is its own document, so a stop leaves the earlier ones to undo. */
    @Test
    fun `a stop part-way leaves each earlier step as its own commit`() = runTest {
        segmentation.failNext(AppError.Unavailable)
        val events = runner.run(
            EditPlan(
                listOf(
                    PlanStep.Adjust(AdjustKind.Exposure, BRIGHTER),
                    PlanStep.Adjust(AdjustKind.Contrast, LOCAL),
                    PlanStep.Select("face", "n1"),
                ),
            ),
            document(),
            frame(),
            activeMask = null,
            sourceAspect = SOURCE_ASPECT,
        ).toList()

        val committed = events.filterIsInstance<RunEvent.Committed>()
        assertEquals(listOf(0, 1), committed.map { it.index })
        assertEquals(1, committed[0].document.operations.size)
        assertEquals(2, committed[1].document.operations.size)
        assertEquals(RunEvent.Stopped(2, AppError.Unavailable), events.last())
        assertNull(committed.last().document.activeMaskId)
    }

    private suspend fun last(plan: EditPlan, document: EditDocument): EditDocument =
        runner.run(plan, document, frame(), activeMask = null, sourceAspect = SOURCE_ASPECT)
            .toList()
            .filterIsInstance<RunEvent.Committed>()
            .last()
            .document

    private fun document() = EditDocument(id = "p", source = ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private fun frame(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private companion object {
        const val SOURCE_ASPECT = 1.5f
        const val SIZE = 32
        const val FULL = 100
        const val TOLERANCE = 0.001f
        const val RAISED = 0.4f
        const val BRIGHTER = 0.55f
        const val LOCAL = 0.2f
        /** `FakeSegmentationProvider`'s phrase that finds nothing. */
        const val ABSENT = "없음"

        val STYLE_PARAMS = mapOf(AdjustKind.Temperature to 0.2f, AdjustKind.Contrast to 0.1f)
    }
}
