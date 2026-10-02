package com.diffuse.feature.editor.tools.multishot

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.Availability
import com.diffuse.core.ai.MaskBitmaps
import com.diffuse.core.ai.PointPrompt
import com.diffuse.core.ai.SegMask
import com.diffuse.core.ai.SegSession
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HeroMask
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Timeline
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.TimelineLayout
import com.diffuse.core.imaging.render.MultiShotLayout
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.withMultiShot
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.TestDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * specs/multishot.md §2, §8, §9 requirement 27. The controller over a scripted SAM 3 and a host
 * whose import, extraction and save can be held open, so every race is decided by the test: what
 * is uploaded and when, what one commit holds, and what a late answer may still touch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// One controller's state machine over one pair of scripted fakes; splitting would copy the fakes.
@Suppress("LargeClass")
class MultiShotControllerTest {

    private val segmentation = ScriptedSegmentation()
    private val host = FakeHost()
    private val settings = MutableStateFlow<Any>("initial")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- entering and importing ----------------------------------------------------------------

    @Test
    fun `entering and adding a photo send nothing`() = runTest {
        val controller = controller()

        controller.start()
        assertTrue(controller.state.value.open)
        assertTrue(controller.state.value.items.isEmpty())
        assertFalse(controller.state.value.canApply)

        controller.onPhotoPicked(URI, replaceKey = null)

        val item = controller.state.value.items.single()
        assertEquals(ShotStatus.Picked, item.status)
        assertTrue("the photo itself is on the canvas", controller.state.value.photo != null)
        assertEquals(0, segmentation.opens)
        assertEquals(0, host.releases)
        assertFalse(controller.state.value.canApply)
    }

    @Test
    fun `a cancelled picker keeps the draft exactly as it was`() = runTest {
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        val before = controller.state.value

        controller.onPhotoPicked(null, null)
        controller.onPhotoPicked(null, before.items.single().key)

        assertEquals(before, controller.state.value)
    }

    @Test
    fun `an unreadable photo is reported and adds nothing`() = runTest {
        val controller = controller()
        controller.start()
        host.loadResult = Result.Failure(AppError.Unsupported)

        controller.onPhotoPicked(URI, null)

        assertTrue(controller.state.value.items.isEmpty())
        assertEquals(R.string.multishot_import_unsupported, controller.state.value.message)
    }

    // ---- extraction ----------------------------------------------------------------------------

    @Test
    fun `several people wait for a choice, then one save and one commit`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf(), rightHalf())
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)

        controller.extract()

        assertEquals(1, host.releases)
        assertEquals(1, segmentation.opens)
        assertEquals(listOf(PERSON), segmentation.texts)
        assertEquals(ShotStatus.Selecting, controller.state.value.selected?.status)
        assertEquals(2, controller.state.value.candidateCount)
        assertNull("no candidate is taken for the user", controller.state.value.mask)
        assertFalse(controller.state.value.canFinishExtraction)

        controller.chooseCandidate(1)
        assertTrue(controller.state.value.canFinishExtraction)
        controller.finishExtraction()

        assertEquals(ShotStatus.Ready, controller.state.value.selected?.status)
        assertEquals(1, host.saves.size)
        assertEquals(listOf(segmentation.lastSession), segmentation.closed)
        assertTrue("a draft is not a commit", host.committed.isEmpty())
        val draft = controller.draftDocument()!!.multiShot()!!.shots.single()
        assertEquals(0.6f, draft.placement.opacity)
        // The subject is the chosen right half, feathered: left transparent, right opaque.
        val subject = host.saves.single().second
        assertEquals(0, Color.alpha(subject.getPixel(2, 10)))
        assertEquals(255, Color.alpha(subject.getPixel(PHOTO_W - 3, 10)))

        val entry = host.document
        controller.apply()

        val committed = host.committed.single()
        assertEquals(draft, committed.multiShot()!!.shots.single())
        assertEquals(entry.operations + committed.multiShot()!!, committed.operations)
        assertEquals("the selection is untouched", entry.activeMaskId, committed.activeMaskId)
        assertTrue("the committed file is not discarded", host.discarded.isEmpty())
        assertFalse(controller.state.value.open)
    }

    @Test
    fun `an empty answer is not a subject until the user picks one`() = runTest {
        segmentation.answers[PERSON] = emptyList()
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)

        controller.extract()

        assertEquals(ShotStatus.Selecting, controller.state.value.selected?.status)
        assertEquals(R.string.multishot_not_found, controller.state.value.selected?.problem)
        assertNull(controller.state.value.mask)
        assertFalse(controller.state.value.canFinishExtraction)

        controller.addPoint(0.25f, 0.5f, include = true)

        assertEquals(1, segmentation.points)
        assertTrue(controller.state.value.canFinishExtraction)
    }

    @Test
    fun `a golf club found by phrase is added to the person`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf())
        segmentation.answers[CLUB] = listOf(bottomRightCorner(), rightHalf())
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        controller.extract()

        controller.setPhrase(CLUB)
        controller.submitPhrase()
        assertEquals(2, controller.state.value.candidateCount)
        controller.chooseCandidate(0)

        val mask = controller.state.value.mask!!
        assertEquals(255, MaskBitmaps.alphaAt(mask, 2, 2))
        assertEquals(255, MaskBitmaps.alphaAt(mask, PHOTO_W - 2, PHOTO_H - 2))
        assertEquals(0, MaskBitmaps.alphaAt(mask, PHOTO_W - 2, 2))
        assertEquals(listOf(PERSON, CLUB), segmentation.texts)
    }

    @Test
    fun `a failure on one photo keeps the other's subject`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)
        controller.onPhotoPicked(URI, null)
        segmentation.failOpen = AppError.Unavailable

        controller.extract()

        val (first, second) = controller.state.value.items
        assertEquals(ShotStatus.Ready, first.status)
        assertEquals(ShotStatus.Picked, second.status)
        assertEquals(R.string.multishot_server_unreachable, second.problem)
        assertEquals(1, controller.state.value.draftShots.size)
        assertFalse("an unfinished photo is never left out silently", controller.state.value.canApply)

        controller.remove(second.key)
        assertTrue(controller.state.value.canApply)
    }

    @Test
    fun `a cancelled extraction ignores the late session and closes it`() = runTest {
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        val gate = CompletableDeferred<Unit>().also { segmentation.openGate = it }

        controller.extract()
        assertTrue(controller.state.value.working)
        controller.cancelWork()
        assertFalse(controller.state.value.working)
        gate.complete(Unit)

        assertEquals(ShotStatus.Picked, controller.state.value.selected?.status)
        assertNull(controller.state.value.selected?.problem)
        assertTrue("nothing was asked of the late session", segmentation.texts.isEmpty())
        assertEquals(listOf(segmentation.lastSession), segmentation.closed)
    }

    @Test
    fun `a slow import that outlives the sheet never lands`() = runTest {
        val controller = controller()
        controller.start()
        val gate = CompletableDeferred<Unit>().also { host.loadGate = it }
        controller.onPhotoPicked(URI, null)

        controller.close()
        gate.complete(Unit)

        assertFalse(controller.state.value.open)
        assertTrue(controller.state.value.items.isEmpty())
    }

    @Test
    fun `a save that finishes after the sheet closed is discarded`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf())
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        controller.extract()
        val gate = CompletableDeferred<Unit>().also { host.saveGate = it }

        controller.finishExtraction()
        controller.close()
        gate.complete(Unit)

        // Discarded once the write came back, since the session that owned it is gone.
        assertEquals(1, host.saves.size)
        assertEquals(host.saves.single().first, host.discarded.last())
        assertTrue(host.committed.isEmpty())
    }

    @Test
    fun `new server settings stop an extraction in progress`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf())
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        controller.extract()

        settings.value = "changed"

        assertEquals(ShotStatus.Picked, controller.state.value.selected?.status)
        assertEquals(R.string.multishot_settings_changed, controller.state.value.selected?.problem)
        assertEquals(listOf(segmentation.lastSession), segmentation.closed)
    }

    @Test
    fun `a document change ends the session and discards what it wrote`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)
        val changed = host.document.copy(updatedAt = 9L)

        assertTrue(controller.onDocumentChanged(changed))

        assertFalse(controller.state.value.open)
        assertEquals(host.saves.map { it.first }, host.discarded)
    }

    // ---- placement and presets -----------------------------------------------------------------

    @Test
    fun `the afterimage preset changes opacity only and stays adjustable`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)
        controller.moveBy(0.1f, -0.05f)
        controller.setScale(10f)
        readyShot(controller)
        val before = controller.state.value.items

        controller.applyAfterimage()

        val after = controller.state.value.items
        assertEquals(listOf(0.35f, 0.65f), after.map { it.placement.opacity })
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(
            before.map { it.placement.copy(opacity = 0f) },
            after.map { it.placement.copy(opacity = 0f) },
        )
        assertEquals("scale is clamped to the shared range", 4f, after.first().placement.scale)

        controller.setOpacity(0.8f)
        assertEquals(0.8f, controller.state.value.selected!!.placement.opacity)
    }

    @Test
    fun `order changes the drawing order`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)
        readyShot(controller)
        val (first, second) = controller.state.value.items

        controller.move(first.key, towardsFront = true)

        assertEquals(listOf(second.key, first.key), controller.state.value.draftShots.map { it.id })
    }

    @Test
    fun `invisible shots cannot be applied as a new composite`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)

        controller.setOpacity(0f)

        assertFalse(controller.state.value.canApply)
    }

    // ---- re-editing ----------------------------------------------------------------------------

    @Test
    fun `reopening restores the stored composite and segments nothing`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()

        controller.start()

        val items = controller.state.value.items
        assertEquals(listOf(STORED_A.id, STORED_B.id), items.map { it.key })
        assertTrue(items.all { it.status == ShotStatus.Ready })
        assertEquals(STORED_B.placement, items[1].placement)
        assertEquals(listOf(STORED_A, STORED_B), controller.state.value.draftShots)
        assertEquals(0, segmentation.opens)
        assertEquals(2, host.thumbnails)
    }

    @Test
    fun `removing every stored shot applies as one removal`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()
        controller.start()

        controller.remove(STORED_A.id)
        controller.remove(STORED_B.id)
        assertTrue(controller.state.value.canApply)
        controller.apply()

        assertNull(host.committed.single().multiShot())
        assertTrue("stored files are the document's, not the session's", host.discarded.isEmpty())
    }

    @Test
    fun `replacing a stored shot issues a new ref and keeps its place in the order`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf())
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()
        controller.start()

        controller.onPhotoPicked(URI, STORED_A.id)
        assertEquals(ShotStatus.Picked, controller.state.value.items.first().status)
        assertFalse(controller.state.value.canApply)
        controller.extract()
        controller.finishExtraction()
        controller.apply()

        val shots = host.committed.single().multiShot()!!.shots
        assertEquals(listOf(STORED_A.id, STORED_B.id), shots.map { it.id })
        assertNotEquals(STORED_A.subjectRef, shots.first().subjectRef)
        assertEquals(STORED_A.placement, shots.first().placement)
    }

    // ---- REVIEW R1: a failed or cancelled replacement --------------------------------------------

    @Test
    fun `a replacement that will not read keeps both stored shots for 적용`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()
        controller.start()
        host.loadResult = Result.Failure(AppError.Unsupported)

        controller.onPhotoPicked(URI, STORED_A.id)

        assertEquals(R.string.multishot_import_unsupported, controller.state.value.message)
        assertEquals(listOf(STORED_A, STORED_B), controller.state.value.draftShots)
        assertTrue(controller.state.value.canApply)
        controller.apply()
        assertEquals(listOf(STORED_A, STORED_B), host.committed.single().multiShot()!!.shots)
        assertTrue(host.discarded.isEmpty())
    }

    @Test
    fun `a failed replacement of the only stored shot does not remove the composite`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A))
        val controller = controller()
        controller.start()
        host.loadResult = Result.Failure(AppError.Io(java.io.IOException("gone")))

        controller.onPhotoPicked(URI, STORED_A.id)
        controller.apply()

        assertEquals(listOf(STORED_A), host.committed.single().multiShot()!!.shots)
    }

    @Test
    fun `a replacement cancelled while reading restores the shot, its place and the preview`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()
        controller.start()
        val gate = CompletableDeferred<Unit>().also { host.loadGate = it }

        controller.onPhotoPicked(URI, STORED_B.id)
        assertEquals(listOf(STORED_A), controller.state.value.draftShots)
        controller.cancelWork()
        gate.complete(Unit)

        assertEquals(listOf(STORED_A, STORED_B), controller.state.value.draftShots)
        assertEquals(listOf(STORED_A, STORED_B), controller.draftDocument()!!.multiShot()!!.shots)
        assertEquals(ShotStatus.Ready, controller.state.value.items[1].status)
        controller.apply()
        assertEquals(listOf(STORED_A, STORED_B), host.committed.single().multiShot()!!.shots)
    }

    @Test
    fun `a failed replacement of a new subject keeps its file and its transform`() = runTest {
        val controller = controller()
        controller.start()
        readyShot(controller)
        controller.moveBy(0.2f, 0.1f)
        val draft = controller.state.value.draftShots.single()
        host.loadResult = Result.Failure(AppError.Unsupported)

        controller.onPhotoPicked(URI, draft.id)
        controller.apply()

        assertEquals(listOf(draft), host.committed.single().multiShot()!!.shots)
        assertTrue("the committed subject is not the session's to discard", host.discarded.isEmpty())
    }

    // ---- REVIEW R2: a selection never outlives its session -------------------------------------

    @Test
    fun `adding a photo while another is selecting leaves that one retryable`() = runTest {
        segmentation.answers[PERSON] = listOf(leftHalf(), rightHalf())
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        controller.extract()
        val first = controller.state.value.selectedKey!!
        assertEquals(ShotStatus.Selecting, controller.state.value.selected?.status)

        controller.onPhotoPicked(URI, null)
        assertEquals(ShotStatus.Picked, controller.state.value.items.first { it.key == first }.status)
        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.extract()
        controller.finishExtraction()

        controller.select(first)
        assertEquals(ShotStatus.Picked, controller.state.value.selected?.status)
        assertTrue("the user can extract it again", controller.state.value.canExtract)
        controller.extract()
        controller.finishExtraction()
        controller.apply()

        assertEquals(2, host.committed.single().multiShot()!!.shots.size)
        assertEquals(segmentation.opens, segmentation.closed.size)
    }

    @Test
    fun `extracting another photo ends the first one's selection the same way`() = runTest {
        val controller = controller()
        controller.start()
        controller.onPhotoPicked(URI, null)
        val first = controller.state.value.selectedKey!!
        controller.onPhotoPicked(URI, null)
        val second = controller.state.value.selectedKey!!
        controller.select(first)
        segmentation.answers[PERSON] = listOf(leftHalf(), rightHalf())
        controller.extract()
        assertEquals(ShotStatus.Selecting, controller.state.value.selected?.status)

        // Selecting the other photo and extracting it closes the first session.
        controller.select(second)
        controller.extract()

        assertEquals(ShotStatus.Picked, controller.state.value.items.first { it.key == first }.status)
        assertTrue(controller.state.value.items.none { it.status == ShotStatus.Selecting && it.key != second })
    }

    // ---- the time layout -----------------------------------------------------------------------

    @Test
    fun `a new composite asks for its layout before a photo can be added`() = runTest {
        val controller = controller()
        controller.open(host.document)

        assertNull(controller.state.value.mode)
        assertFalse(controller.state.value.canAddPhoto)
        controller.onPhotoPicked(URI, null)
        assertTrue(controller.state.value.items.isEmpty())

        controller.setMode(MultiShotMode.Timeline)
        assertTrue(controller.state.value.canAddPhoto)
        val hero = controller.state.value.hero?.status
        assertEquals("the hero is chosen on the local input canvas", ShotStatus.Picked, hero)
        assertEquals(0, segmentation.opens)
    }

    @Test
    fun `a seventh moment is refused`() = runTest {
        val controller = controller()
        controller.start()
        repeat(5) { controller.onPhotoPicked(URI, null) }
        assertFalse("six in all with the current photo", controller.state.value.canAddPhoto)

        controller.onPhotoPicked(URI, null)

        assertEquals(5, controller.state.value.items.size)
    }

    @Test
    fun `the time layout applies only with a confirmed order and a hero`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        assertFalse("order not confirmed", controller.state.value.canApply)

        controller.placeByOrder()
        assertTrue(controller.state.value.canApply)
        val order = controller.state.value.timeOrder
        controller.apply()

        val composite = host.committed.single().multiShot()!!
        assertEquals(MultiShotMode.Timeline, composite.mode)
        val timeline = composite.timeline!!
        assertEquals(order, timeline.order)
        assertEquals(NormPoint(0.25f, 1f), timeline.hero!!.anchor)
        assertEquals(CANVAS_W, timeline.hero!!.widthPx)
        assertTrue(composite.shots.all { it.anchor == NormPoint(0.25f, 1f) })
        assertTrue(composite.isValid)
        val committedFiles = composite.shots.map { it.subjectRef } + timeline.hero!!.ref
        assertTrue(committedFiles.none { ref -> host.discarded.any { ref.path.endsWith("shot_$it.png") } })
    }

    @Test
    fun `a hero that is not found stays unfinished and blocks 적용`() = runTest {
        val controller = controller()
        timeline(controller, shots = 1, hero = false)
        segmentation.answers[PERSON] = emptyList()

        controller.select(MultiShotState.HERO_KEY)
        controller.extract()

        assertEquals(ShotStatus.Selecting, controller.state.value.hero?.status)
        assertEquals(R.string.multishot_not_found, controller.state.value.hero?.problem)
        assertNull("never the whole photo", controller.state.value.mask)
        assertFalse(controller.state.value.canApply)
        assertEquals(MultiShotMode.Timeline, controller.state.value.mode)
        controller.setMode(MultiShotMode.Free)
        assertTrue("the user may switch explicitly", controller.state.value.canApply)
    }

    @Test
    fun `the hero is selected on the composite's input without the crop`() = runTest {
        host.document = host.document.withCrop(android.graphics.RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)
        val controller = controller()
        controller.open(host.document)

        controller.setMode(MultiShotMode.Timeline)

        val input = host.baseRenders.single()
        assertTrue(input.operations.none { it is com.diffuse.core.imaging.model.Operation.Crop })
        assertEquals(host.document.activeMaskId, input.activeMaskId)
    }

    @Test
    fun `reordering in time moves nothing until it is laid out, and the layout is repeatable`() = runTest {
        val controller = controller()
        timeline(controller, shots = 3)
        controller.placeByOrder()
        val laidOut = controller.state.value.items.map { it.placement }
        controller.placeByOrder()
        assertEquals("no accumulation", laidOut, controller.state.value.items.map { it.placement })

        controller.reverseTime()
        assertEquals(laidOut, controller.state.value.items.map { it.placement })
        assertFalse(controller.state.value.orderConfirmed)
        assertFalse(controller.state.value.canApply)
        assertTrue(controller.state.value.layoutStale)

        controller.placeByOrder()
        assertFalse(controller.state.value.layoutStale)
        val reversed = controller.state.value.items.map { it.placement }
        assertEquals(laidOut.first().copy(opacity = 0f), reversed.last().copy(opacity = 0f))
        assertEquals(laidOut.first().opacity, reversed.last().opacity)
    }

    @Test
    fun `direction and distance move afterimages only, strength changes opacity only`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.setArrangement(TimelineArrangement.Path)
        controller.placeByOrder()
        controller.select(controller.state.value.items.first().key)
        controller.setScale(1.5f)
        controller.setRotation(20f)
        controller.setOpacity(0.9f)
        val before = controller.state.value.items.map { it.placement }

        controller.setDirection(180f)
        val turned = controller.state.value.items.map { it.placement }
        val unmoved = { list: List<ShotPlacement> -> list.map { it.copy(offsetX = 0f, offsetY = 0f) } }
        assertEquals(unmoved(before), unmoved(turned))
        assertNotEquals(before.map { it.offsetX }, turned.map { it.offsetX })

        controller.setStrength(0.5f)
        val faded = controller.state.value.items.map { it.placement }
        assertEquals(turned.map { it.copy(opacity = 0f) }, faded.map { it.copy(opacity = 0f) })
        val opacities = controller.state.value.timeItems.map { it.placement.opacity }
        assertEquals(0.125f, opacities[0], 1e-4f)
        assertEquals(0.35f, opacities[1], 1e-4f)

        controller.setDistance(0f)
        controller.setDistance(0.5f)
        controller.setDirection(180f)
        assertEquals("settings, not offsets, decide", turned.map { it.copy(opacity = 0f) },
            controller.state.value.items.map { it.placement.copy(opacity = 0f) })
    }

    @Test
    fun `the time reset goes back to the slot and keeps opacity, the free reset to the centre`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.placeByOrder()
        val key = controller.state.value.items.first().key
        controller.select(key)
        val slot = controller.state.value.selected!!.placement
        controller.moveBy(0.3f, 0.2f)
        controller.setOpacity(0.8f)

        controller.resetPlacement()
        assertEquals(slot.copy(opacity = 0.8f), controller.state.value.selected!!.placement)

        controller.setMode(MultiShotMode.Free)
        controller.resetPlacement()
        assertEquals(ShotPlacement(opacity = 0.8f), controller.state.value.selected!!.placement)
        assertTrue("the confirmed order survives the switch", controller.state.value.orderConfirmed)
    }

    @Test
    fun `a stored time layout reopens exactly, offline, without laying it out again`() = runTest {
        val hero = HeroMask(ImageRef("/p/shot_h.png"), CANVAS_W, CANVAS_H, NormPoint(0.3f, 0.9f))
        val a = STORED_A.copy(anchor = NormPoint(0.5f, 1f))
        val b = STORED_B.copy(anchor = NormPoint(0.4f, 1f))
        val layout = TimelineLayout(directionDeg = 45f, distance = 0.7f, strength = 0.8f)
        host.document = host.document.withMultiShot(
            listOf(a, b),
            mode = MultiShotMode.Timeline,
            timeline = Timeline(listOf("b", "a"), layout, hero),
        )
        val controller = controller()

        controller.open(host.document)

        val state = controller.state.value
        assertEquals(MultiShotMode.Timeline, state.mode)
        assertEquals(listOf("b", "a"), state.timeOrder)
        assertTrue(state.orderConfirmed)
        assertFalse(state.layoutStale)
        assertEquals(layout, state.layout)
        assertEquals(ShotStatus.Ready, state.hero?.status)
        assertEquals(host.document, controller.draftDocument())
        assertEquals(0, segmentation.opens)
        controller.apply()
        assertEquals(host.document.multiShot(), host.committed.single().multiShot())
    }

    @Test
    fun `a free composite from before offers its drawing order unconfirmed and keeps its placements`() = runTest {
        host.document = host.document.withMultiShot(listOf(STORED_A, STORED_B))
        val controller = controller()
        controller.open(host.document)

        controller.setMode(MultiShotMode.Timeline)

        val state = controller.state.value
        assertEquals(listOf(STORED_A.id, STORED_B.id), state.timeOrder)
        assertFalse(state.orderConfirmed)
        assertEquals(listOf(STORED_A.placement, STORED_B.placement), state.items.map { it.placement })
        assertFalse(state.canApply)
    }

    @Test
    fun `the hero mask is the session's until 적용 and is discarded with it`() = runTest {
        val controller = controller()
        timeline(controller, shots = 1)
        val heroFile = host.saves.last().first

        controller.close()

        assertTrue(heroFile in host.discarded)
    }

    @Test
    fun `an anchor drag moves the anchor and leaves the subject`() = runTest {
        val controller = controller()
        timeline(controller, shots = 1)
        val item = controller.state.value.items.single()
        controller.select(item.key)
        controller.setAnchorEditing(true)

        controller.moveBy(0.05f, -0.1f)

        val moved = controller.state.value.selected!!
        assertEquals(item.placement, moved.placement)
        assertNotEquals(item.anchor, moved.anchor)
        val marker = controller.state.value.anchorMarker!!
        assertEquals(0.05f, marker.x - com.diffuse.core.imaging.render.MultiShotLayout.onCanvas(
            controller.state.value.draftShots.single().copy(anchor = item.anchor), item.anchor!!, CANVAS_W, CANVAS_H,
        ).x, 1e-4f)
    }

    // ---- placing the order on the canvas (tasks.md 2026-10-01, 위치 배치) --------------------------

    @Test
    fun `with an afterimage selected the path places the reversed order left to right`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        val (a, b) = controller.state.value.timeOrder
        val arrangement = controller.state.value.layout.arrangement
        assertEquals("a new proposal is the even arrangement", TimelineArrangement.Even, arrangement)
        controller.setArrangement(TimelineArrangement.Path)
        assertEquals("the path runs left to right", 0f, controller.state.value.layout.directionDeg)
        controller.select(a)
        val opens = segmentation.opens

        controller.setPanel(TimelinePanel.Layout)
        assertTrue(controller.state.value.layoutShown)
        assertEquals(2, controller.state.value.slots.size)
        val before = controller.state.value.items.map { it.placement }
        controller.reverseTime()
        assertEquals("reordering moves nothing", before, controller.state.value.items.map { it.placement })
        assertTrue(controller.state.value.layoutStale)
        assertFalse(controller.state.value.canApply)

        controller.placeByOrder()

        val state = controller.state.value
        assertEquals(listOf(b, a), state.timeOrder)
        assertTrue(state.orderConfirmed)
        assertTrue(state.positionsCurrent)
        val anchors = state.draftShots.associate {
            it.id to MultiShotLayout.onCanvas(it, it.anchor!!, CANVAS_W, CANVAS_H)
        }
        val hero = state.heroAnchor!!
        assertTrue("oldest left of the newer one", anchors.getValue(b).x < anchors.getValue(a).x)
        assertTrue("the newest afterimage left of the hero", anchors.getValue(a).x < hero.x)
        assertEquals(hero.y, anchors.getValue(a).y, 1e-4f)
        assertEquals(hero.y, anchors.getValue(b).y, 1e-4f)
        assertTrue(state.slots.all { it.occupied })
        assertEquals(0.25f, state.timeItems.first().placement.opacity, 1e-4f)
        assertEquals(0.7f, state.timeItems.last().placement.opacity, 1e-4f)
        val draft = state.draftShots
        controller.apply()

        val composite = host.committed.single().multiShot()!!
        assertEquals(listOf(b, a), composite.timeline!!.order)
        assertEquals(draft, composite.shots)
        assertEquals("placing calls no segmentation", opens, segmentation.opens)
    }

    // ---- 장수별 균등 배치 (tasks.md 2026-10-01, D088) ---------------------------------------------

    @Test
    fun `three in all then six in all place the shown order in different even slots`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        val hero = controller.state.value.heroAnchor!!
        val (a, b) = controller.state.value.timeOrder
        controller.select(a)
        controller.setPanel(TimelinePanel.Layout)
        assertEquals(3, controller.state.value.totalMoments)

        controller.placeByOrder()

        // [A, 원본, B]: W / 3 apart around the hero, all on its height.
        assertAnchors(controller, hero, mapOf(a to hero.x - 1f / 3, b to hero.x + 1f / 3))
        val threeInAll = controller.state.value.items.associate { it.key to it.placement }

        repeat(3) { readyShot(controller) }
        val (c, d, e) = controller.state.value.timeOrder.drop(2)
        assertTrue("three new photos ask for the order again", controller.state.value.layoutStale)
        assertEquals("nothing moves before the user places", threeInAll.getValue(a),
            controller.state.value.items.first { it.key == a }.placement)
        controller.setPanel(TimelinePanel.Layout)
        assertEquals(6, controller.state.value.totalMoments)
        assertEquals(5, controller.state.value.slots.size)

        controller.placeByOrder()

        // [A, B, 원본, C, D, E]: W / 6 apart, the hero in the third slot.
        val sixth = 1f / 6
        assertAnchors(
            controller,
            hero,
            mapOf(
                a to hero.x - 2 * sixth,
                b to hero.x - sixth,
                c to hero.x + sixth,
                d to hero.x + 2 * sixth,
                e to hero.x + 3 * sixth,
            ),
        )
        val opacities = controller.state.value.timeItems.map { it.placement.opacity }
        listOf(0.25f, 0.3625f, 0.475f, 0.5875f, 0.7f).zip(opacities).forEach { (want, got) ->
            assertEquals(want, got, 1e-4f)
        }
        assertTrue(controller.state.value.canApply)
        controller.apply()
        val composite = host.committed.single().multiShot()!!
        assertEquals(5, composite.shots.size)
        assertEquals(TimelineArrangement.Even, composite.timeline!!.layout.arrangement)
        assertTrue(composite.isValid)
    }

    @Test
    fun `removing one and placing again uses the new count, reversing swaps slots`() = runTest {
        val controller = controller()
        timeline(controller, shots = 4)
        val hero = controller.state.value.heroAnchor!!
        controller.placeByOrder()
        val removed = controller.state.value.timeOrder.last()
        controller.remove(removed)
        controller.reverseTime()

        controller.placeByOrder()

        // Four in all: [x, 원본, y, z] in the reversed order, W / 4 apart.
        val order = controller.state.value.timeOrder
        assertAnchors(
            controller,
            hero,
            mapOf(order[0] to hero.x - 0.25f, order[1] to hero.x + 0.25f, order[2] to hero.x + 0.5f),
        )
    }

    @Test
    fun `spacing moves positions around the hero only and keeps size and angle`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.placeByOrder()
        val a = controller.state.value.timeOrder.first()
        controller.select(a)
        controller.setScale(1.5f)
        controller.setRotation(30f)
        controller.setOpacity(0.9f)
        val hero = controller.state.value.heroAnchor!!

        controller.setSpacing(0.5f)

        val item = controller.state.value.items.first { it.key == a }
        assertEquals(1.5f, item.placement.scale)
        assertEquals(30f, item.placement.rotationDeg)
        assertEquals(0.9f, item.placement.opacity)
        val shot = controller.state.value.draftShots.first { it.id == a }
        val anchor = MultiShotLayout.onCanvas(shot, shot.anchor!!, CANVAS_W, CANVAS_H)
        assertEquals(hero.x - 0.5f / 3, anchor.x, 1e-4f)
        assertEquals(hero.y, anchor.y, 1e-4f)
    }

    private fun assertAnchors(controller: MultiShotController, hero: NormPoint, xs: Map<String, Float>) {
        val shots = controller.state.value.draftShots.associateBy { it.id }
        xs.forEach { (id, x) ->
            val shot = shots.getValue(id)
            val anchor = MultiShotLayout.onCanvas(shot, shot.anchor!!, CANVAS_W, CANVAS_H)
            assertEquals("$id x", x, anchor.x, 1e-4f)
            assertEquals("$id on the hero's height", hero.y, anchor.y, 1e-4f)
        }
    }

    @Test
    fun `placing waits for the hero and every photo and says which one is missing`() = runTest {
        val controller = controller()
        timeline(controller, shots = 1, hero = false)
        controller.onPhotoPicked(URI, null)
        controller.setPanel(TimelinePanel.Layout)

        assertEquals(PlaceBlocker(R.string.multishot_needs_hero), controller.state.value.placeBlocker)
        controller.placeByOrder()
        assertFalse("nothing is confirmed by a blocked press", controller.state.value.orderConfirmed)

        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.select(MultiShotState.HERO_KEY)
        controller.extract()
        controller.finishExtraction()
        assertEquals(PlaceBlocker(R.string.multishot_needs_extraction, 2), controller.state.value.placeBlocker)
        assertFalse(controller.state.value.canPlace)
        assertEquals(MultiShotMode.Timeline, controller.state.value.mode)
        assertEquals(2, controller.state.value.items.size)
    }

    @Test
    fun `keeping the current positions confirms the order and moves nothing`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.setPanel(TimelinePanel.Layout)
        val before = controller.state.value.items.map { it.placement }

        controller.keepPositions()

        assertTrue(controller.state.value.orderConfirmed)
        assertTrue(controller.state.value.keptPositions)
        assertTrue(controller.state.value.canApply)
        assertEquals(before, controller.state.value.items.map { it.placement })
        controller.apply()
        assertEquals(before, host.committed.single().multiShot()!!.shots.map { it.placement })
    }

    @Test
    fun `a photo moved by hand leaves its slot, and placing again puts it back`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.placeByOrder()
        val placed = controller.state.value.items.map { it.placement }
        val first = controller.state.value.timeOrder.first()
        controller.select(first)
        controller.setPanel(TimelinePanel.Layout)
        controller.setPanel(TimelinePanel.Photos)

        controller.moveBy(0.1f, 0f)
        controller.setPanel(TimelinePanel.Layout)
        assertFalse(controller.state.value.slots.first().occupied)
        assertTrue(controller.state.value.slots.last().occupied)

        controller.placeByOrder()
        assertEquals(placed, controller.state.value.items.map { it.placement })
        assertTrue(controller.state.value.slots.all { it.occupied })
    }

    @Test
    fun `cancelling after placing leaves the document as it was`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        val entry = host.document
        controller.placeByOrder()

        controller.close()

        assertTrue(host.committed.isEmpty())
        assertEquals(entry, host.document)
        assertEquals(host.saves.map { it.first }, host.discarded)
    }

    @Test
    fun `the layout step shows the composite and ends an open selection`() = runTest {
        val controller = controller()
        timeline(controller, shots = 1)
        segmentation.answers[PERSON] = listOf(leftHalf(), rightHalf())
        controller.onPhotoPicked(URI, null)
        controller.extract()
        assertEquals(ShotStatus.Selecting, controller.state.value.selected?.status)

        controller.setPanel(TimelinePanel.Layout)

        assertEquals(ShotStatus.Picked, controller.state.value.selected?.status)
        assertNull("the canvas shows the composite, not the photo", controller.state.value.photo)
        assertEquals(segmentation.opens, segmentation.closed.size)
    }

    // ---- REVIEW R2 (2026-10-01): free edits never cost the hero ----------------------------------

    @Test
    fun `removing a photo in the free layout keeps the hero and settings and only asks for the order`() = runTest {
        val controller = controller()
        timeline(controller, shots = 2)
        controller.setDirection(45f)
        controller.placeByOrder()
        val heroFile = host.saves.last().first
        val heroAnchor = controller.state.value.heroAnchor
        controller.setMode(MultiShotMode.Free)
        val (gone, kept) = controller.state.value.items
        controller.remove(gone.key)

        assertTrue(controller.state.value.canApply)
        controller.apply()

        val composite = host.committed.single().multiShot()!!
        assertEquals(MultiShotMode.Free, composite.mode)
        assertTrue(composite.isValid)
        val timeline = composite.timeline!!
        assertFalse("the changed order is not confirmed behind the user's back", timeline.orderConfirmed)
        assertEquals(listOf(kept.key), timeline.order)
        assertEquals(heroAnchor, timeline.hero!!.anchor)
        assertTrue(timeline.hero!!.ref.path.endsWith("shot_$heroFile.png"))
        assertEquals(45f, timeline.layout.directionDeg)
        assertEquals(kept.placement, composite.shots.single().placement)
        assertFalse("the committed hero file is the document's", heroFile in host.discarded)

        // Reopened and taken back to the time layout: the hero is there, the order is asked for.
        val opens = segmentation.opens
        val reopened = controller()
        reopened.open(host.document)
        reopened.setMode(MultiShotMode.Timeline)
        val state = reopened.state.value
        assertEquals(ShotStatus.Ready, state.hero?.status)
        assertEquals(heroAnchor, state.heroAnchor)
        assertFalse(state.orderConfirmed)
        assertFalse(state.canApply)
        val positions = reopened.state.value.items.map { it.placement }
        reopened.keepPositions()
        assertTrue(reopened.state.value.canApply)
        assertEquals(positions, reopened.state.value.items.map { it.placement })
        assertEquals("nothing is segmented again", opens, segmentation.opens)
    }

    @Test
    fun `adding or replacing a photo of a stored time layout in the free layout keeps its hero`() = runTest {
        val hero = HeroMask(ImageRef("/p/shot_h.png"), CANVAS_W, CANVAS_H, NormPoint(0.3f, 0.9f))
        val layout = TimelineLayout(directionDeg = 45f, distance = 0.7f, strength = 0.8f)
        val a = STORED_A.copy(anchor = NormPoint(0.5f, 1f))
        val b = STORED_B.copy(anchor = NormPoint(0.4f, 1f))
        host.document = host.document.withMultiShot(
            listOf(a, b),
            mode = MultiShotMode.Timeline,
            timeline = Timeline(listOf("b", "a"), layout, hero),
        )
        val controller = controller()
        controller.open(host.document)
        controller.setMode(MultiShotMode.Free)

        readyShot(controller)
        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.onPhotoPicked(URI, a.id)
        controller.extract()
        controller.finishExtraction()
        controller.apply()

        val composite = host.committed.single().multiShot()!!
        assertTrue(composite.isValid)
        assertEquals(hero, composite.timeline!!.hero)
        assertEquals(layout, composite.timeline!!.layout)
        assertFalse(composite.timeline!!.orderConfirmed)
        assertEquals(listOf("b", "a", composite.shots.last().id), composite.timeline!!.order)
        assertEquals(b, composite.shots[1])
    }

    @Test
    fun `cancelling free edits of a stored time layout leaves its files alone`() = runTest {
        val hero = HeroMask(ImageRef("/p/shot_h.png"), CANVAS_W, CANVAS_H, NormPoint(0.3f, 0.9f))
        host.document = host.document.withMultiShot(
            listOf(STORED_A.copy(anchor = NormPoint(0.5f, 1f)), STORED_B.copy(anchor = NormPoint(0.5f, 1f))),
            mode = MultiShotMode.Timeline,
            timeline = Timeline(listOf("a", "b"), TimelineLayout(), hero),
        )
        val controller = controller()
        controller.open(host.document)
        controller.setMode(MultiShotMode.Free)
        controller.remove(STORED_A.id)
        readyShot(controller)
        val written = host.saves.map { it.first }

        controller.close()

        assertEquals("only the session's own subject goes", written, host.discarded)
        assertTrue(host.committed.isEmpty())
    }

    // ---- 여러 장 선택 → 한 번의 실행 → 자동 균등 배치 (tasks.md 2026-10-02) ------------------------

    @Test
    fun `three in all - several photos in one pick, one press, laid out evenly without another tap`() = runTest {
        val controller = controller()
        newTimeline(controller)

        pick(controller, uris(2))

        val picked = controller.state.value
        assertEquals("one pick, two photos", 2, picked.items.size)
        assertTrue(picked.items.all { it.status == ShotStatus.Picked })
        assertEquals(uris(2), host.loads)
        assertEquals("choosing and picking send nothing", 0, segmentation.opens)
        val (a, b) = picked.timeOrder
        // The hero off-centre on the left, the two photos with other subjects.
        answer(listOf(leftHalf()), listOf(middle()), listOf(rightHalf()))

        controller.runAll()

        val state = controller.state.value
        assertNull("the run is over", state.run)
        assertFalse(state.working)
        assertEquals(ShotStatus.Ready, state.hero?.status)
        assertTrue(state.items.all { it.status == ShotStatus.Ready })
        assertEquals("the hero first, then the photos, one session each", 3, segmentation.opens)
        assertEquals(List(3) { PERSON }, segmentation.texts)
        assertEquals(3, segmentation.closed.size)
        assertTrue(state.orderConfirmed)
        assertTrue(state.positionsCurrent)
        assertEquals(TimelinePanel.Layout, state.panel)
        val hero = state.heroAnchor!!
        assertEquals(0.25f, hero.x, 0.03f)
        // [A, 원본, B] on the anchors of the stored placements, at the hero's height.
        assertAnchors(controller, hero, mapOf(a to hero.x - 1f / 3, b to hero.x + 1f / 3))
        assertEquals(listOf(0.25f, 0.7f), state.timeItems.map { it.placement.opacity })
        assertTrue(state.timeItems.all { it.placement.scale == 1f && it.placement.rotationDeg == 0f })
        assertTrue("no tap between the press and 적용", state.canApply)

        val draft = state.draftShots
        controller.apply()

        val composite = host.committed.single().multiShot()!!
        assertEquals(MultiShotMode.Timeline, composite.mode)
        assertEquals(listOf(a, b), composite.timeline!!.order)
        assertEquals(hero, composite.timeline!!.hero!!.anchor)
        assertEquals(draft, composite.shots)
        assertTrue(composite.isValid)
        assertTrue("committed files are the document's", host.discarded.isEmpty())
    }

    @Test
    fun `six in all - five photos of other sizes go to their slots in the picked order`() = runTest {
        val controller = controller()
        newTimeline(controller)
        host.sizes[uri(2)] = 60 to 20
        host.sizes[uri(4)] = 20 to 40
        val request = controller.requestPick(null)!!
        assertEquals("five more fit", 5, request.max)
        // The picker's order is kept as it is, a repeated photo included.
        val picked = listOf(uri(3), uri(1), uri(2), uri(4), uri(1))
        controller.onPicked(request.id, picked)
        assertEquals(picked, host.loads)
        val order = controller.state.value.timeOrder
        assertEquals(5, order.distinct().size)
        assertFalse(controller.state.value.canAddPhoto)
        answer(
            listOf(middle()), listOf(leftHalf()), listOf(middle()),
            listOf(rightHalf()), listOf(middle()), listOf(leftHalf()),
        )

        controller.runAll()

        val state = controller.state.value
        assertEquals(6, segmentation.opens)
        assertTrue(state.positionsCurrent)
        val hero = state.heroAnchor!!
        val sixth = 1f / 6
        // [A, B, 원본, C, D, E]: the hero keeps the third slot and does not move.
        assertAnchors(
            controller,
            hero,
            mapOf(
                order[0] to hero.x - 2 * sixth,
                order[1] to hero.x - sixth,
                order[2] to hero.x + sixth,
                order[3] to hero.x + 2 * sixth,
                order[4] to hero.x + 3 * sixth,
            ),
        )
        listOf(0.25f, 0.3625f, 0.475f, 0.5875f, 0.7f).zip(state.timeItems.map { it.placement.opacity })
            .forEach { (want, got) -> assertEquals(want, got, 1e-4f) }
        val resized = state.draftShots.filter { it.id == order[2] || it.id == order[3] }
        assertEquals(listOf(60, 20), resized.map { it.widthPx })
        assertTrue(state.canApply)
        controller.apply()
        val composite = host.committed.single().multiShot()!!
        assertEquals(order, composite.timeline!!.order)
        assertTrue(composite.isValid)
    }

    @Test
    fun `several people stop the run on that photo, and the choice resumes it to the layout`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        val (a, b) = controller.state.value.timeOrder
        answer(listOf(leftHalf()), listOf(leftHalf(), rightHalf()), listOf(middle()))

        controller.runAll()

        val paused = controller.state.value
        assertEquals(RunProgress(2, 3, RunPhase.Choosing), paused.run)
        assertEquals(a, paused.selectedKey)
        assertEquals(ShotStatus.Selecting, paused.selected?.status)
        assertEquals(2, paused.candidateCount)
        assertNull("no candidate is taken for the user", paused.mask)
        assertFalse("not an endless spinner", paused.working)
        assertEquals("the next photo waits", 2, segmentation.opens)
        assertEquals(ShotStatus.Picked, paused.items.first { it.key == b }.status)
        assertNull("nothing is laid out yet", paused.laidOutOrder)
        assertFalse(paused.canApply)

        // A second press, another photo or the layout step: nothing happens while it waits.
        controller.runAll()
        controller.select(b)
        controller.setPanel(TimelinePanel.Layout)
        controller.reverseTime()
        assertEquals(paused.copy(), controller.state.value)
        assertEquals(2, segmentation.opens)

        controller.chooseCandidate(1)
        controller.finishExtraction()

        val state = controller.state.value
        assertNull(state.run)
        assertEquals(3, segmentation.opens)
        assertTrue(state.positionsCurrent)
        val hero = state.heroAnchor!!
        assertAnchors(controller, hero, mapOf(a to hero.x - 1f / 3, b to hero.x + 1f / 3))
        assertTrue(state.canApply)
    }

    @Test
    fun `a hero nobody was found on waits for points, then the run goes on`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        answer(emptyList(), listOf(leftHalf()), listOf(rightHalf()))

        controller.runAll()

        val paused = controller.state.value
        assertEquals(MultiShotState.HERO_KEY, paused.selectedKey)
        assertEquals(RunPhase.Choosing, paused.run?.phase)
        assertEquals(R.string.multishot_not_found, paused.hero?.problem)
        assertEquals(1, segmentation.opens)

        controller.addPoint(0.25f, 0.5f, include = true)
        controller.finishExtraction()

        assertNull(controller.state.value.run)
        assertEquals(3, segmentation.opens)
        assertTrue(controller.state.value.canApply)
    }

    @Test
    fun `a failure stops at that photo, keeps the others, and the retry sends only what is left`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(3))
        val (_, b, c) = controller.state.value.timeOrder
        segmentation.answers[PERSON] = listOf(leftHalf())
        segmentation.failOpens[3] = AppError.Unavailable

        controller.runAll()

        val failed = controller.state.value
        assertNull(failed.run)
        assertEquals("nothing after the failed photo is sent", 3, segmentation.opens)
        assertEquals(b, failed.selectedKey)
        assertEquals(ShotStatus.Picked, failed.selected?.status)
        assertEquals(R.string.multishot_server_unreachable, failed.selected?.problem)
        assertEquals(ShotStatus.Picked, failed.items.first { it.key == c }.status)
        assertEquals(ShotStatus.Ready, failed.hero?.status)
        assertNull("never laid out with a photo missing", failed.laidOutOrder)
        assertTrue(failed.items.all { it.placement == ShotPlacement() })
        assertFalse(failed.canApply)
        assertTrue("retry, replace and delete are open", failed.canRun && !failed.busy)

        controller.runAll()

        assertEquals("only the failed and the remaining photo", 5, segmentation.opens)
        assertTrue(controller.state.value.positionsCurrent)
        assertTrue(controller.state.value.canApply)
    }

    @Test
    fun `진행 취소 stops the queue, the late upload is closed and nothing is laid out`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        segmentation.answers[PERSON] = listOf(leftHalf())
        val gate = CompletableDeferred<Unit>().also { segmentation.openGates[2] = it }
        controller.runAll()
        assertEquals(RunPhase.Extracting, controller.state.value.run?.phase)

        controller.cancelRun()
        gate.complete(Unit)

        val state = controller.state.value
        assertNull(state.run)
        assertFalse(state.working)
        assertEquals("no next upload", 2, segmentation.opens)
        assertEquals("the late session was asked nothing", listOf(PERSON), segmentation.texts)
        assertEquals(2, segmentation.closed.size)
        assertEquals(ShotStatus.Ready, state.hero?.status)
        assertTrue(state.items.all { it.status == ShotStatus.Picked })
        assertNull(state.laidOutOrder)
        assertTrue(host.committed.isEmpty())
    }

    @Test
    fun `new server settings end the run, a save under way finishes and nothing more is sent`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        segmentation.answers[PERSON] = listOf(leftHalf())
        val gate = CompletableDeferred<Unit>().also { host.saveGate = it }
        controller.runAll()
        assertEquals(RunPhase.Saving, controller.state.value.run?.phase)

        settings.value = "other server"
        gate.complete(Unit)

        assertNull(controller.state.value.run)
        assertEquals(ShotStatus.Ready, controller.state.value.hero?.status)
        assertEquals("the photos wait for a new press", 1, segmentation.opens)
        assertNull(controller.state.value.laidOutOrder)
    }

    @Test
    fun `closing during a save discards its file and the run never resumes`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        segmentation.answers[PERSON] = listOf(leftHalf())
        val gate = CompletableDeferred<Unit>().also { host.saveGate = it }
        controller.runAll()

        controller.close()
        gate.complete(Unit)

        assertEquals(1, segmentation.opens)
        assertEquals(1, host.saves.size)
        assertEquals("the written file goes", setOf(host.saves.single().first), host.discarded.toSet())
        assertFalse(controller.state.value.open)
        assertTrue(host.committed.isEmpty())
    }

    @Test
    fun `the hero still being prepared is waited for, then the run goes on`() = runTest {
        val gate = CompletableDeferred<Unit>().also { host.baseGate = it }
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(2))
        assertEquals(ShotStatus.Importing, controller.state.value.hero?.status)
        segmentation.answers[PERSON] = listOf(leftHalf())

        controller.runAll()
        assertEquals(RunPhase.Preparing, controller.state.value.run?.phase)
        assertEquals(0, segmentation.opens)

        gate.complete(Unit)

        assertNull(controller.state.value.run)
        assertEquals(3, segmentation.opens)
        assertTrue(controller.state.value.positionsCurrent)
    }

    @Test
    fun `with everything extracted the press lays out locally, and corrections stay until pressed again`() =
        runTest {
            val controller = controller()
            timeline(controller, shots = 2)
            val opens = segmentation.opens
            assertTrue(controller.state.value.canRun)

            controller.runAll()

            assertEquals("no network", opens, segmentation.opens)
            assertTrue(controller.state.value.positionsCurrent)
            val laidOut = controller.state.value.items.associate { it.key to it.placement }
            val a = controller.state.value.timeOrder.first()
            controller.select(a)
            controller.moveBy(0.1f, 0.05f)
            controller.setScale(1.5f)
            controller.setRotation(20f)
            val corrected = controller.state.value.items.first { it.key == a }.placement
            // Selecting, the layout step and back: nothing lays itself out again.
            controller.select(controller.state.value.timeOrder.last())
            controller.setPanel(TimelinePanel.Layout)
            controller.select(a)
            assertEquals(corrected, controller.state.value.items.first { it.key == a }.placement)
            assertTrue(controller.state.value.canApply)

            controller.runAll()

            val again = controller.state.value.items.first { it.key == a }.placement
            assertEquals(1.5f, again.scale)
            assertEquals(20f, again.rotationDeg)
            assertEquals(laidOut.getValue(a).opacity, again.opacity)
            val shot = controller.state.value.draftShots.first { it.id == a }
            val anchor = MultiShotLayout.onCanvas(shot, shot.anchor!!, CANVAS_W, CANVAS_H)
            assertEquals(controller.state.value.heroAnchor!!.x - 1f / 3, anchor.x, 1e-4f)
            assertEquals(opens, segmentation.opens)
        }

    @Test
    fun `a photo added after the layout says so and the press extracts only it before laying out again`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(1))
        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.runAll()
        assertEquals(2, segmentation.opens)

        pick(controller, uris(1, from = 7))
        val changed = controller.state.value
        assertFalse(changed.positionsCurrent)
        assertTrue(changed.pending)
        assertFalse(changed.canApply)

        controller.runAll()

        assertEquals("only the new photo is sent", 3, segmentation.opens)
        val (a, b) = controller.state.value.timeOrder
        val hero = controller.state.value.heroAnchor!!
        assertAnchors(controller, hero, mapOf(a to hero.x - 1f / 3, b to hero.x + 1f / 3))
        assertTrue(controller.state.value.canApply)
    }

    @Test
    fun `the picker is limited to what still fits, and a refused, empty or late answer changes nothing`() = runTest {
        val controller = controller()
        newTimeline(controller)
        pick(controller, uris(4))
        assertEquals("one place left: a single pick", 1, controller.requestPick(null)!!.max)
        assertEquals("a replacement is one photo", 1, controller.requestPick(controller.state.value.items[0].key)!!.max)
        val before = controller.state.value

        // A fallback picker that ignored the limit: refused whole, not cut short.
        val request = controller.requestPick(null)!!
        controller.onPicked(request.id, uris(2, from = 10))
        assertEquals(before.items, controller.state.value.items)
        assertEquals(R.string.multishot_too_many, controller.state.value.message)
        controller.onMessageShown()

        // Backing out, and an answer to a request that is no longer the last one.
        val stale = controller.requestPick(null)!!
        controller.onPicked(controller.requestPick(null)!!.id, emptyList())
        controller.onPicked(stale.id, uris(1, from = 20))
        assertEquals(before.items, controller.state.value.items)

        // An answer that arrives after the sheet was closed and opened again.
        val late = controller.requestPick(null)!!
        controller.close()
        newTimeline(controller)
        controller.onPicked(late.id, uris(1, from = 30))
        assertTrue(controller.state.value.items.isEmpty())
        assertEquals(0, segmentation.opens)

        pick(controller, uris(5))
        assertNull("six in all", controller.requestPick(null))
    }

    @Test
    fun `the free layout still picks one photo at a time and has no run`() = runTest {
        val controller = controller()
        controller.start()

        assertEquals(1, controller.requestPick(null)!!.max)
        pick(controller, uris(1))
        controller.runAll()

        assertEquals(0, segmentation.opens)
        assertEquals(ShotStatus.Picked, controller.state.value.items.single().status)
    }

    @Test
    fun `an unreadable photo in a pick is named and kept, and the run waits for 교체 or 삭제`() = runTest {
        val controller = controller()
        newTimeline(controller)
        host.unreadable += uri(2)

        pick(controller, uris(3))

        val state = controller.state.value
        val statuses = state.timeItems.map { it.status }
        assertEquals(listOf(ShotStatus.Picked, ShotStatus.Unreadable, ShotStatus.Picked), statuses)
        assertEquals(R.string.multishot_import_unsupported, state.timeItems[1].problem)
        assertFalse(state.canRun)
        controller.runAll()
        assertEquals(0, segmentation.opens)

        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.remove(state.timeItems[1].key)
        assertTrue(controller.state.value.canRun)
        controller.runAll()
        assertEquals(3, segmentation.opens)
        assertTrue(controller.state.value.canApply)
    }

    @Test
    fun `a pick cancelled while reading keeps the photos already read`() = runTest {
        val controller = controller()
        newTimeline(controller)
        val gate = CompletableDeferred<Unit>().also { host.loadGate = it }

        pick(controller, uris(3))
        controller.cancelWork()
        gate.complete(Unit)

        assertTrue(controller.state.value.items.isEmpty())
        assertTrue(controller.state.value.timeOrder.isEmpty())
        assertFalse(controller.state.value.working)
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** The time layout with [shots] placed photos and, unless [hero] is false, a selected hero. */
    private fun timeline(controller: MultiShotController, shots: Int, hero: Boolean = true) {
        controller.open(host.document)
        controller.setMode(MultiShotMode.Timeline)
        repeat(shots) { readyShot(controller) }
        if (hero) {
            segmentation.answers[PERSON] = listOf(leftHalf())
            controller.select(MultiShotState.HERO_KEY)
            controller.extract()
            controller.finishExtraction()
            assertEquals(ShotStatus.Ready, controller.state.value.hero?.status)
        }
    }

    /** A new time layout on the host's document, as the user starts one. */
    private fun newTimeline(controller: MultiShotController) {
        controller.open(host.document)
        controller.setMode(MultiShotMode.Timeline)
    }

    /** What the route does: asks for a pick and hands back the picker's answer, in its order. */
    private fun pick(controller: MultiShotController, uris: List<Uri>): PickRequest {
        val request = controller.requestPick(null)!!
        controller.onPicked(request.id, uris)
        return request
    }

    private fun uris(count: Int, from: Int = 1): List<Uri> = (from until from + count).map(::uri)

    /** One "person" answer per photo the run will send, the hero's first. */
    private fun answer(vararg masks: List<Bitmap>) {
        segmentation.queue.addAll(masks)
    }

    /** Unconfined, like `viewModelScope` over the test Main, and cancelled with the test. */
    private fun TestScope.controller() = MultiShotController(
        segmentation = segmentation,
        settingsChanges = settings,
        serverHost = flowOf("http://sam"),
        host = host,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        dispatchers = TestDispatchers,
    )

    /** Opens on the host's document; a new composite chooses the free layout, as these tests did. */
    private fun MultiShotController.start() {
        open(host.document)
        setMode(MultiShotMode.Free)
    }

    /** Adds a photo and takes it all the way to a placed subject. */
    private fun readyShot(controller: MultiShotController) {
        segmentation.answers[PERSON] = listOf(leftHalf())
        controller.onPhotoPicked(URI, null)
        controller.extract()
        controller.finishExtraction()
        assertEquals(ShotStatus.Ready, controller.state.value.selected?.status)
    }

    private class ScriptedSegmentation : SegmentationProvider {
        override val availability = MutableStateFlow<Availability>(Availability.Ready)
        val answers = mutableMapOf<String, List<Bitmap>>()
        val texts = mutableListOf<String>()
        val closed = mutableListOf<SegSession>()
        var opens = 0
        var points = 0
        var lastSession: SegSession? = null
        var openGate: CompletableDeferred<Unit>? = null
        var failOpen: AppError? = null

        /** "person" answers, one per query in turn, before [answers] is used. */
        val queue = ArrayDeque<List<Bitmap>>()

        /** The nth upload (1-based) waits for its gate, or fails. */
        val openGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failOpens = mutableMapOf<Int, AppError>()

        override suspend fun refresh() = Unit

        override suspend fun open(image: Bitmap): Result<SegSession> {
            val n = ++opens
            // Ignores cancellation, the way a request already on the wire does.
            openGate?.let { withContext(NonCancellable) { it.await() } }
            openGates[n]?.let { withContext(NonCancellable) { it.await() } }
            (failOpen ?: failOpens[n])?.let { return Result.Failure(it) }
            val session = SegSession("s$opens", image.width, image.height, Long.MAX_VALUE)
            lastSession = session
            return Result.Success(session)
        }

        override suspend fun byPoints(session: SegSession, prompt: PointPrompt): Result<SegMask> {
            points++
            return Result.Success(SegMask(leftHalf(), 0.9f))
        }

        override suspend fun byText(session: SegSession, phrase: String): Result<List<SegMask>> {
            texts += phrase
            val answer = if (phrase == PERSON && queue.isNotEmpty()) queue.removeFirst() else answers[phrase].orEmpty()
            return Result.Success(answer.map { SegMask(it, 0.9f) })
        }

        override suspend fun close(session: SegSession) {
            closed += session
        }
    }

    private class FakeHost : MultiShotHost {
        var document = EditDocument("p", ImageRef("/p/source.jpg"), createdAt = 0L, updatedAt = 0L)
            .withMask(ImageRef("/p/mask_m.png"), "m")
        val committed = mutableListOf<EditDocument>()
        val saves = mutableListOf<Pair<String, Bitmap>>()
        val discarded = mutableListOf<String>()
        var releases = 0
        var thumbnails = 0
        var loadResult: Result<Bitmap>? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        val baseRenders = mutableListOf<EditDocument>()
        var baseResult: Bitmap? = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
        var baseGate: CompletableDeferred<Unit>? = null
        val loads = mutableListOf<Uri>()
        val unreadable = mutableSetOf<Uri>()

        /** Photos of another size than [PHOTO_W] × [PHOTO_H], by URI. */
        val sizes = mutableMapOf<Uri, Pair<Int, Int>>()

        override suspend fun loadPhoto(uri: Uri): Result<Bitmap> {
            loads += uri
            loadGate?.let { withContext(NonCancellable) { it.await() } }
            if (uri in unreadable) return Result.Failure(AppError.Unsupported)
            val (width, height) = sizes[uri] ?: (PHOTO_W to PHOTO_H)
            return loadResult ?: Result.Success(
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            )
        }

        override suspend fun saveSubject(fileId: String, subject: Bitmap): Result<ImageRef> {
            saves += fileId to subject
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            return Result.Success(ImageRef("/p/shot_$fileId.png"))
        }

        override suspend fun discardSubjects(fileIds: List<String>) {
            discarded += fileIds
        }

        override suspend fun thumbnailOf(ref: ImageRef, longEdgePx: Int): Bitmap {
            thumbnails++
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }

        override suspend fun releaseSelection() {
            releases++
        }

        override suspend fun renderBase(document: EditDocument): Bitmap? {
            baseRenders += document
            baseGate?.await()
            return baseResult
        }

        override fun currentDocument(): EditDocument = document

        override fun commit(document: EditDocument) {
            committed += document
            this.document = document
        }
    }

    private companion object {
        const val PHOTO_W = 40
        const val PHOTO_H = 30
        const val CANVAS_W = 40
        const val CANVAS_H = 30
        const val PERSON = "person"
        const val CLUB = "골프채"
        val URI: Uri = Uri.parse("content://media/picker/1")
        val STORED_A = Shot("a", ImageRef("/p/shot_a.png"), 40, 30, ShotPlacement(offsetX = 0.1f))
        val STORED_B = Shot("b", ImageRef("/p/shot_b.png"), 40, 30, ShotPlacement(scale = 2f, opacity = 0.3f))

        fun uri(index: Int): Uri = Uri.parse("content://media/picker/$index")

        fun leftHalf(): Bitmap = region { x, _ -> x < PHOTO_W / 2 }

        /** A person standing off the bottom edge, in the photo's middle columns. */
        fun middle(): Bitmap = region { x, y -> x in 10 until 30 && y < PHOTO_H - 6 }

        fun rightHalf(): Bitmap = region { x, _ -> x >= PHOTO_W / 2 }

        fun bottomRightCorner(): Bitmap = region { x, y -> x >= PHOTO_W - 5 && y >= PHOTO_H - 5 }

        fun region(inside: (Int, Int) -> Boolean): Bitmap =
            Bitmap.createBitmap(PHOTO_W, PHOTO_H, Bitmap.Config.ALPHA_8).apply {
                for (y in 0 until PHOTO_H) for (x in 0 until PHOTO_W) {
                    setPixel(x, y, if (inside(x, y)) Color.BLACK else Color.TRANSPARENT)
                }
            }
    }
}
