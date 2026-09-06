package com.diffuse.feature.editor.tools.direct

import android.graphics.Bitmap
import app.cash.turbine.test
import com.diffuse.core.ai.CropRatio
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.FakeEraseProvider
import com.diffuse.core.ai.FakeSegmentationProvider
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.Operation
import com.diffuse.feature.editor.tools.erase.EraseCommit
import com.diffuse.feature.editor.tools.select.MaskOps
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

/** specs/vibe_edit.md §9, §12. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanRunnerTest {

    private val segmentation = FakeSegmentationProvider(openDelayMs = 0)
    private val eraser = FakeEraseProvider()
    private val savedMasks = mutableListOf<String>()
    private val savedErases = mutableListOf<String>()
    private var failMaskWrite = false

    private val dispatchers = object : DispatcherProvider {
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private val runner = PlanRunner(
        segmentation = segmentation,
        erase = eraser,
        dispatchers = dispatchers,
        saveMask = { maskId, _ ->
            if (failMaskWrite) {
                Result.Failure(AppError.Unavailable)
            } else {
                savedMasks += maskId
                Result.Success(ImageRef("/p/mask_$maskId.png"))
            }
        },
        eraseCommit = EraseCommit(
            saveMask = { maskId, _ -> Result.Success(ImageRef("/p/mask_$maskId.png")) },
            saveResult = { eraseId, _ ->
                savedErases += eraseId
                Result.Success(ImageRef("/p/erase_$eraseId.png"))
            },
        ),
    )

    // ---- §9.1 validation --------------------------------------------------

    @Test
    fun `a masked adjust with no selection anywhere is rejected`() {
        val step = PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true)

        assertEquals(step, runner.validate(EditPlan(listOf(step)), document()))
    }

    @Test
    fun `an erase and a cut-out with no selection are rejected`() {
        assertEquals(
            PlanStep.Erase,
            runner.validate(EditPlan(listOf(PlanStep.Erase)), document()),
        )
        assertEquals(
            PlanStep.CutOut,
            runner.validate(EditPlan(listOf(PlanStep.CutOut)), document()),
        )
    }

    @Test
    fun `an earlier Select in the same plan satisfies all three`() {
        val plan = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
                PlanStep.Erase,
                PlanStep.CutOut,
            ),
        )

        assertNull(runner.validate(plan, document()))
    }

    @Test
    fun `an active mask on the document satisfies all three`() {
        val document = documentWithMask()

        assertNull(
            runner.validate(
                EditPlan(
                    listOf(
                        PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
                        PlanStep.Erase,
                        PlanStep.CutOut,
                    ),
                ),
                document,
            ),
        )
    }

    @Test
    fun `an unmasked adjust needs no selection at all`() {
        val plan = EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false)))

        assertNull(runner.validate(plan, document()))
    }

    // ---- §9.2, §9.3 running -----------------------------------------------

    @Test
    fun `the happy path emits Started and Committed per step, then Completed`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
        )

        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).test {
            assertEquals(RunEvent.Started(0), awaitItem())
            val first = awaitItem() as RunEvent.Committed
            assertEquals(1, first.document.operations.size)
            assertEquals(RunEvent.Started(1), awaitItem())
            val second = awaitItem() as RunEvent.Committed
            assertEquals(2, second.document.operations.size)
            assertEquals(RunEvent.Completed, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a masked adjust carries the mask the Select just made`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
        )

        val document = lastDocument(plan)

        val mask = document.operations.filterIsInstance<Operation.Mask>().single()
        val adjust = document.operations.filterIsInstance<Operation.Adjust>().single()
        assertEquals(mask.id, document.activeMaskId)
        assertEquals(mask.id, adjust.maskId)
    }

    @Test
    fun `an unmasked adjust carries no mask id`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
            ),
        )

        val adjust = lastDocument(plan).operations.filterIsInstance<Operation.Adjust>().single()

        assertNull(adjust.maskId)
    }

    @Test
    fun `an erase and a cut-out run against the selection the plan made`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Select("나무"), PlanStep.Erase, PlanStep.CutOut))

        val document = lastDocument(plan)

        // T50: the erase references the margin mask it was actually run through, while the
        // selection the plan made stays active for the cut-out that follows.
        val selected = document.activeMaskId
        val erase = document.generativeErases().single()
        assertNotEquals(selected, erase.maskId)
        assertEquals(selected, document.cutOuts().single().maskId)
        assertEquals(2, document.operations.filterIsInstance<Operation.Mask>().size)
        assertEquals(1, eraser.eraseCount)
        assertEquals(savedErases, document.generativeErases().map { it.id })
    }

    /** specs/vibe_edit.md §9.2 + T51: the eraser is told what was removed. */
    @Test
    fun `the erase hint is the phrase the Select used`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Select("bus"), PlanStep.Erase))

        lastDocument(plan)

        assertEquals("bus", eraser.lastHint)
    }

    @Test
    fun `an erase with no Select in the plan has no hint to give`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Erase))

        runner.run(plan, documentWithMask(), preview(), activeMask = fullMask(), sourceAspect = SOURCE_ASPECT).toList()

        assertNull(eraser.lastHint)
    }

    @Test
    fun `the mask the eraser was shown is larger than the selection`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Select("나무"), PlanStep.Erase))

        lastDocument(plan)

        // FakeSegmentationProvider's circles are the selection; what the eraser was handed is
        // that union grown by EraseMask's margin, so it covers strictly more pixels.
        val handed = eraser.lastMask!!
        val selection = MaskOps.union(
            segmentation.byText(session(), "나무").let { (it as Result.Success).value }
                .map { mask -> mask.alpha },
        )!!
        assertTrue(
            "the eraser was handed ${setPixels(handed)} px for a ${setPixels(selection)} px selection",
            setPixels(handed) > setPixels(selection),
        )
    }

    @Test
    fun `an erase with no Select uses the mask the document already had`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Erase))
        val document = documentWithMask()

        val events = runner
            .run(plan, document, preview(), fullMask(), SOURCE_ASPECT)
            .toList()

        val committed = events.filterIsInstance<RunEvent.Committed>().single()
        // T50: the erase stores the margin mask it ran through, and leaves the user's selection
        // active for whatever comes next.
        assertEquals(document.activeMaskId, committed.document.activeMaskId)
        assertNotEquals(document.activeMaskId, committed.document.generativeErases().single().maskId)
        assertEquals(1, eraser.eraseCount)
        assertEquals(RunEvent.Completed, events.last())
    }

    @Test
    fun `a Select that finds nothing stops the run before anything is committed`() = runTest {
        val plan = EditPlan(
            listOf(PlanStep.Select("없음"), PlanStep.Adjust(AdjustKind.Saturation, 0.3f, true)),
        )

        val events = runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).toList()

        assertEquals(
            listOf(
                RunEvent.Started(0),
                RunEvent.Stopped(0, AppError.Invalid("${PlanRunner.NOT_FOUND_PREFIX}없음")),
            ),
            events,
        )
    }

    @Test
    fun `a failure at step 2 leaves step 1 committed`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
                PlanStep.Select("나무"),
                PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
            ),
        )
        failMaskWrite = true

        val events = runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).toList()

        val committed = events.filterIsInstance<RunEvent.Committed>().single()
        assertEquals(0, committed.index)
        assertEquals(1, committed.document.operations.size)
        assertEquals(RunEvent.Stopped(1, AppError.Unavailable), events.last())
    }

    @Test
    fun `a failing step reports its own error and nothing after it runs`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Select("나무"),
                PlanStep.Erase,
                PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
            ),
        )
        eraser.failNext(AppError.Invalid("blocked:SAFETY"))

        val events = runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).toList()

        assertEquals(RunEvent.Stopped(1, AppError.Invalid("blocked:SAFETY")), events.last())
        assertEquals(1, events.filterIsInstance<RunEvent.Committed>().size)
    }

    @Test
    fun `cancelling mid-step commits nothing for that step`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
                PlanStep.Select("나무"),
            ),
        )

        val events = mutableListOf<RunEvent>()
        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).test {
            events += awaitItem()
            events += awaitItem()
            events += awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(RunEvent.Started(1), events.last())
        assertTrue(events.filterIsInstance<RunEvent.Committed>().none { it.index == 1 })
    }

    // ---- §9.2: the session ------------------------------------------------

    @Test
    fun `one session is opened for the whole run and closed when it ends`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Select("나무"), PlanStep.Select("하늘")))

        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).toList()

        assertEquals(1, segmentation.openCount)
        assertEquals(emptyList<Any>(), segmentation.openSessions)
    }

    @Test
    fun `a plan with no Select opens no session at all`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false)))

        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT).toList()

        assertEquals(0, segmentation.openCount)
    }

    // ---- §4.1 crop_ratio (T58) -------------------------------------------

    @Test
    fun `a Crop step needs no selection`() {
        val step = PlanStep.Crop(CropRatio.Square)

        assertNull(runner.validate(EditPlan(listOf(step)), document()))
    }

    @Test
    fun `a Crop step commits a centred rect at the requested ratio`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Crop(CropRatio.Square)))

        val crop = lastDocument(plan).operations.filterIsInstance<Operation.Crop>().single()

        // A 2:1 source cropped to 1:1 keeps its full height and half its width, centred.
        assertEquals(0f, crop.angleDeg, 0f)
        assertEquals(0.25f, crop.rect.left, TOLERANCE)
        assertEquals(0.75f, crop.rect.right, TOLERANCE)
        assertEquals(0f, crop.rect.top, TOLERANCE)
        assertEquals(1f, crop.rect.bottom, TOLERANCE)
    }

    @Test
    fun `the crop lands last, after the steps before it`() = runTest {
        val plan = EditPlan(
            listOf(
                PlanStep.Adjust(AdjustKind.Exposure, 0.5f, masked = false),
                PlanStep.Crop(CropRatio.Story9x16),
            ),
        )

        val ops = lastDocument(plan).operations

        assertTrue("the adjust must survive the crop", ops.any { it is Operation.Adjust })
        assertTrue("the crop is the last op", ops.last() is Operation.Crop)
    }

    @Test
    fun `a Crop step opens no segmentation session`() = runTest {
        val plan = EditPlan(listOf(PlanStep.Crop(CropRatio.Portrait4x5)))

        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT)
            .toList()

        assertEquals(0, segmentation.openCount)
    }

    // ---- fixtures ---------------------------------------------------------

    private suspend fun lastDocument(plan: EditPlan): EditDocument =
        runner.run(plan, document(), preview(), activeMask = null, sourceAspect = SOURCE_ASPECT)
            .toList()
            .filterIsInstance<RunEvent.Committed>()
            .last()
            .document

    private fun document() = EditDocument(
        id = "p",
        source = ImageRef("/p.jpg"),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun documentWithMask() = document()
        .withMask(ImageRef("/p/mask_m.png"), id = "m")

    private fun preview(): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private suspend fun session() =
        (segmentation.open(preview()) as Result.Success).value

    private fun setPixels(mask: Bitmap): Int {
        var count = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if ((mask.getPixel(x, y) ushr ALPHA_SHIFT) != 0) count++
            }
        }
        return count
    }

    private fun fullMask(): Bitmap =
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply {
            for (pixel in 0 until SIZE * SIZE) {
                setPixel(pixel % SIZE, pixel / SIZE, OPAQUE shl ALPHA_SHIFT)
            }
        }

    private companion object {
        /** A 2:1 source, so a centred 1:1 crop is an easy number to assert. */
        const val SOURCE_ASPECT = 2f
        const val TOLERANCE = 0.001f

        const val SIZE = 32
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
