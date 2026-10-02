package com.diffuse.core.imaging.model

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** specs/multishot.md §3, §6, §7: where the op goes, what guards it, and what a load refuses. */
@RunWith(AndroidJUnit4::class)
class MultiShotModelTest {

    private val empty = EditDocument("d", ImageRef("/p/source.jpg"), createdAt = 0L, updatedAt = 0L)

    // ---- position ----------------------------------------------------------------------------

    @Test
    fun `a new composite is appended and an edit replaces it in place`() {
        val adjusted = empty.withAdjust(AdjustKind.Exposure, 0.3f)
        val added = adjusted.withMultiShot(listOf(SHOT), id = "m")
        val later = added.withAdjust(AdjustKind.Contrast, 0.2f)

        val edited = later.withMultiShot(listOf(SHOT.copy(placement = ShotPlacement(opacity = 0.9f))))

        assertTrue(added.operations.last() is Operation.MultiShot)
        assertEquals(1, edited.operations.indexOfFirst { it is Operation.MultiShot })
        assertEquals("m", edited.multiShot()!!.id)
        assertEquals(0.9f, edited.multiShot()!!.shots.single().placement.opacity)
        assertEquals(later.operations.size, edited.operations.size)
    }

    @Test
    fun `an in-place adjust keeps its position before the composite`() {
        // §3's stated limit: an Adjust that already existed before the composite stays there when
        // it is changed, so it keeps applying to the background only.
        val document = empty.withAdjust(AdjustKind.Exposure, 0.3f).withMultiShot(listOf(SHOT))

        val changed = document.withAdjust(AdjustKind.Exposure, 0.6f)

        assertTrue(changed.operations[0] is Operation.Adjust)
        assertTrue(changed.operations[1] is Operation.MultiShot)
    }

    @Test
    fun `an empty shot list removes the composite and is a no-op without one`() {
        val composited = empty.withMultiShot(listOf(SHOT))

        assertNull(composited.withMultiShot(emptyList()).multiShot())
        assertSame(empty.operations, empty.withMultiShot(emptyList()).operations)
    }

    @Test
    fun `a crop stays where it was and the source is untouched`() {
        val cropped = empty.withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)

        val composited = cropped.withMultiShot(listOf(SHOT))

        assertEquals(cropped.source, composited.source)
        assertEquals(cropped.crop(), composited.crop())
        assertNull(composited.activeMaskId)
    }

    // ---- guards ------------------------------------------------------------------------------

    @Test
    fun `a composite blocks outpainting and an outpaint may come before it`() {
        val composited = empty.withMultiShot(listOf(SHOT))

        assertFalse(composited.canOutpaint)
        assertSame(composited, composited.withOutpaint(Margins(0.1f, 0f, 0.1f, 0f), ImageRef("/p/o.png")))

        val outpainted = empty.withOutpaint(Margins(0.1f, 0f, 0.1f, 0f), ImageRef("/p/o.png"))
        val both = outpainted.withMultiShot(listOf(SHOT))
        assertTrue(both.operations[0] is Operation.Outpaint)
        assertTrue(both.referencesResolve())
    }

    @Test
    fun `placement ranges are the shared ones`() {
        assertTrue(ShotPlacement(offsetX = 3f, scale = 0.1f, rotationDeg = -180f, opacity = 0f).isValid)
        assertFalse(ShotPlacement(scale = 4.01f).isValid)
        assertFalse(ShotPlacement(rotationDeg = 181f).isValid)
        assertFalse(ShotPlacement(opacity = 1.01f).isValid)
        assertFalse(ShotPlacement(offsetY = Float.NaN).isValid)
        assertEquals(
            ShotPlacement(scale = 4f, rotationDeg = 180f, opacity = 0f),
            ShotPlacement(scale = 9f, rotationDeg = 400f, opacity = -1f).coerced(),
        )
    }

    @Test
    fun `an invalid or second composite does not resolve`() {
        val composited = empty.withMultiShot(listOf(SHOT))
        assertTrue(composited.referencesResolve())

        // specs/multishot.md §7: five earlier moments at most, six in all; a four-shot one still loads.
        val four = composited.copy(operations = listOf(Operation.MultiShot("m", List(4) { SHOT.copy(id = "s$it") })))
        assertTrue(four.referencesResolve())
        val five = composited.copy(operations = listOf(Operation.MultiShot("m", List(5) { SHOT.copy(id = "s$it") })))
        assertTrue(five.referencesResolve())
        val six = composited.copy(operations = listOf(Operation.MultiShot("m", List(6) { SHOT.copy(id = "s$it") })))
        val duplicate = composited.copy(operations = listOf(Operation.MultiShot("m", listOf(SHOT, SHOT))))
        val twice = composited.copy(operations = composited.operations + Operation.MultiShot("n", listOf(SHOT)))
        val noRef = composited.withMultiShot(listOf(SHOT.copy(subjectRef = ImageRef(""))))
        val infinite = composited.withMultiShot(
            listOf(SHOT.copy(placement = ShotPlacement(offsetX = Float.POSITIVE_INFINITY))),
        )

        listOf(six, duplicate, twice, noRef, infinite).forEach { assertFalse(it.referencesResolve()) }
    }

    @Test
    fun `a time layout needs an order of exactly its shots, valid settings, a hero and anchors`() {
        val shots = listOf(TIMED_A, TIMED_B)
        val good = Operation.MultiShot("m", shots, MultiShotMode.Timeline, TIMELINE)
        assertTrue(good.isValid)

        val broken = listOf(
            good.copy(timeline = null),
            good.copy(timeline = TIMELINE.copy(hero = null)),
            good.copy(timeline = TIMELINE.copy(order = listOf("a"))),
            good.copy(timeline = TIMELINE.copy(order = listOf("a", "a"))),
            good.copy(timeline = TIMELINE.copy(order = listOf("a", "x"))),
            good.copy(timeline = TIMELINE.copy(layout = TimelineLayout(distance = 2.5f))),
            good.copy(timeline = TIMELINE.copy(layout = TimelineLayout(strength = Float.NaN))),
            good.copy(timeline = TIMELINE.copy(hero = HERO.copy(widthPx = 0))),
            good.copy(timeline = TIMELINE.copy(hero = HERO.copy(anchor = NormPoint(1.2f, 0.5f)))),
            good.copy(shots = listOf(TIMED_A, TIMED_B.copy(anchor = null))),
            good.copy(shots = listOf(TIMED_A, TIMED_B.copy(anchor = NormPoint(0.5f, Float.NaN)))),
            good.copy(timeline = TIMELINE.copy(orderConfirmed = false)),
            good.copy(timeline = TIMELINE.copy(layout = TimelineLayout(spacing = 1.2f))),
            good.copy(timeline = TIMELINE.copy(layout = TimelineLayout(spacing = Float.NaN))),
        )
        broken.forEach { assertFalse("refused: $it", it.isValid) }
        // The free layout keeps a confirmed order without needing a hero or anchors.
        val keptOrder = Timeline(listOf("b", "a"))
        assertTrue(Operation.MultiShot("m", listOf(SHOT, SHOT.copy(id = "b")), timeline = keptOrder).isValid)
    }

    @Test
    fun `the time layout draws in time order, the free one in list order`() {
        val shots = listOf(TIMED_A, TIMED_B)
        val reversed = TIMELINE.copy(order = listOf("b", "a"))

        val timed = Operation.MultiShot("m", shots, MultiShotMode.Timeline, reversed)
        val free = Operation.MultiShot("m", shots, MultiShotMode.Free, reversed)

        assertEquals(listOf("b", "a"), timed.drawingOrder.map { it.id })
        assertEquals(listOf("a", "b"), free.drawingOrder.map { it.id })
    }

    @Test
    fun `the composite's input is everything before it without the crop`() {
        val document = empty.withAdjust(AdjustKind.Exposure, 0.3f)
            .withMask(ImageRef("/p/mask_m.png"), "m")
            .withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f)
            .withMultiShot(listOf(SHOT))
            .withAdjust(AdjustKind.Contrast, 0.2f)
            .withMask(ImageRef("/p/mask_n.png"), "n")

        val base = document.multiShotBase()

        assertEquals(
            listOf(Operation.Adjust::class, Operation.Mask::class, Operation.Mask::class),
            base.operations.map { it::class },
        )
        assertEquals(AdjustKind.Exposure, (base.operations[0] as Operation.Adjust).kind)
        // A new composite is appended, so its input is the whole list without the crop.
        assertEquals(
            listOf(Operation.Adjust::class, Operation.Mask::class),
            empty.withAdjust(AdjustKind.Exposure, 0.3f).withMask(ImageRef("/p/m.png"), "m")
                .withCrop(RectF(0.1f, 0.1f, 0.9f, 0.9f), 0f).multiShotBase().operations.map { it::class },
        )
    }

    // ---- JSON --------------------------------------------------------------------------------

    @Test
    fun `a composite round-trips through JSON`() {
        val document = empty.withAdjust(AdjustKind.Exposure, 0.3f).withMultiShot(
            listOf(
                SHOT,
                SHOT.copy(
                    id = "b",
                    subjectRef = ImageRef("/p/shot_b.png"),
                    placement = ShotPlacement(-0.2f, 0.4f, 2.5f, -45f, 0.35f),
                ),
            ),
        )

        assertEquals(document, EditDocumentJson.decode(EditDocumentJson.encode(document)))
    }

    @Test
    fun `a time layout round-trips through JSON`() {
        val document = empty.withMultiShot(
            listOf(TIMED_A, TIMED_B),
            mode = MultiShotMode.Timeline,
            timeline = TIMELINE,
        )

        assertEquals(document, EditDocumentJson.decode(EditDocumentJson.encode(document)))
        val free = empty.withMultiShot(listOf(TIMED_A, TIMED_B), timeline = TIMELINE.copy(hero = null))
        assertEquals(free, EditDocumentJson.decode(EditDocumentJson.encode(free)))
        // REVIEW R2: the free layout keeps the hero and settings with an order still to confirm.
        val unconfirmed = empty.withMultiShot(
            listOf(TIMED_A, TIMED_B),
            timeline = TIMELINE.copy(orderConfirmed = false),
        )
        assertTrue(unconfirmed.referencesResolve())
        assertEquals(unconfirmed, EditDocumentJson.decode(EditDocumentJson.encode(unconfirmed)))
        assertFalse(EditDocumentJson.encode(document).contains("orderConfirmed"))
        // D088: the even arrangement and its spacing round-trip; the path writes neither field.
        val even = empty.withMultiShot(
            listOf(TIMED_A, TIMED_B),
            mode = MultiShotMode.Timeline,
            timeline = TIMELINE.copy(layout = TimelineLayout(arrangement = TimelineArrangement.Even, spacing = 0.8f)),
        )
        assertEquals(even, EditDocumentJson.decode(EditDocumentJson.encode(even)))
        assertFalse(EditDocumentJson.encode(document).contains("arrangement"))
        assertFalse(EditDocumentJson.encode(document).contains("spacing"))
    }

    @Test
    fun `a composite saved before the time layout loads as the free layout and is written back alike`() {
        val shot = """{"id":"a","subjectRef":"/p/shot_a.png","width":40,"height":30,""" +
            """"offsetX":0.1,"offsetY":0,"scale":1,"rotationDeg":0,"opacity":0.6}"""
        val legacy = documentJson("""{"type":"multiShot","id":"m","shots":[$shot]}""")

        val decoded = EditDocumentJson.decode(legacy)
        val composite = decoded.multiShot()!!

        assertTrue(decoded.referencesResolve())
        assertEquals(MultiShotMode.Free, composite.mode)
        assertNull(composite.timeline)
        assertNull(composite.shots.single().anchor)
        assertEquals(0.1f, composite.shots.single().placement.offsetX)
        val written = EditDocumentJson.encode(decoded)
        assertEquals(written, EditDocumentJson.encode(EditDocumentJson.decode(written)))
        assertFalse(EditDocumentJson.encode(decoded).contains("timeline"))
        assertFalse(EditDocumentJson.encode(decoded).contains("mode"))
    }

    @Test
    fun `a known time layout that is broken is kept broken so the load refuses it`() {
        val shot = { id: String ->
            """{"id":"$id","subjectRef":"/p/shot_$id.png","width":40,"height":30,"offsetX":0,"offsetY":0,""" +
                """"scale":1,"rotationDeg":0,"opacity":0.6,"anchorX":0.5,"anchorY":1}"""
        }
        val hero = """{"ref":"/p/shot_h.png","width":40,"height":30,"anchorX":0.3,"anchorY":0.9}"""
        val timeline = """{"order":["a","b"],"directionDeg":135,"distance":0.5,"strength":1,"hero":$hero}"""
        val good = """{"type":"multiShot","id":"m","shots":[${shot("a")},${shot("b")}],""" +
            """"mode":"timeline","timeline":$timeline}"""
        val decoded = EditDocumentJson.decode(documentJson(good))
        assertTrue(decoded.referencesResolve())
        assertEquals("a timeline from before D088 is the path", TimelineArrangement.Path,
            decoded.multiShot()!!.timeline!!.layout.arrangement)
        val even = good.replace(""""strength":1""", """"strength":1,"arrangement":"even","spacing":0.8""")
        assertEquals(0.8f, EditDocumentJson.decode(documentJson(even)).multiShot()!!.timeline!!.layout.spacing)

        val cases = listOf(
            good.replace(""","timeline":$timeline""", ""),
            good.replace(""""mode":"timeline"""", """"mode":"spiral""""),
            good.replace(""""order":["a","b"]""", """"order":["a","a"]"""),
            good.replace(""""order":["a","b"]""", """"order":["a"]"""),
            good.replace(""""order":["a","b"],""", ""),
            good.replace(""""distance":0.5,""", ""),
            good.replace(""""strength":1""", """"strength":3"""),
            good.replace(""","hero":$hero""", ""),
            good.replace(""""width":40,"height":30,"anchorX":0.3""", """"height":30,"anchorX":0.3"""),
            good.replace(""""anchorX":0.3,""", ""),
            good.replace(""","anchorX":0.5,"anchorY":1}""", "}"),
            good.replace(""""timeline":$timeline""", """"timeline":7"""),
            good.replace(""""strength":1""", """"strength":1,"orderConfirmed":false"""),
            good.replace(""""strength":1""", """"strength":1,"orderConfirmed":"no""""),
            good.replace(""""strength":1""", """"strength":1,"arrangement":"grid","spacing":1"""),
            good.replace(""""strength":1""", """"strength":1,"arrangement":"even""""),
            good.replace(""""strength":1""", """"strength":1,"arrangement":"even","spacing":1.5"""),
            good.replace(""""strength":1""", """"strength":1,"arrangement":"even","spacing":"wide""""),
        )

        cases.forEach { operation ->
            val decoded = EditDocumentJson.decode(documentJson(operation))
            assertTrue("kept: $operation", decoded.multiShot() != null)
            assertFalse("refused: $operation", decoded.referencesResolve())
        }
    }

    @Test
    fun `a known composite that is broken is kept broken so the load refuses it`() {
        val shot = """{"id":"a","subjectRef":"/p/shot_a.png","width":40,"height":30,""" +
            """"offsetX":0,"offsetY":0,"scale":1,"rotationDeg":0,"opacity":0.6}"""
        val cases = listOf(
            """{"type":"multiShot","id":"m","shots":[]}""",
            """{"type":"multiShot","id":"m","shots":[$shot,$shot]}""",
            """{"type":"multiShot","id":"m","shots":[${shot.replace("\"subjectRef\":\"/p/shot_a.png\",", "")}]}""",
            """{"type":"multiShot","id":"m","shots":[${shot.replace("\"scale\":1", "\"scale\":\"NaN\"")}]}""",
            """{"type":"multiShot","shots":[$shot]}""",
            """{"type":"multiShot","id":"m"}""",
        )

        cases.forEach { operation ->
            val decoded = EditDocumentJson.decode(documentJson(operation))
            assertTrue("kept: $operation", decoded.multiShot() != null)
            assertFalse("refused: $operation", decoded.referencesResolve())
        }
    }

    @Test
    fun `a document from before multi-shot still loads`() {
        val legacy = documentJson("""{"type":"adjust","id":"a","kind":"Exposure","value":0.2}""")

        val decoded = EditDocumentJson.decode(legacy)

        assertNull(decoded.multiShot())
        assertTrue(decoded.referencesResolve())
        assertEquals(0.2f, decoded.adjustValue(AdjustKind.Exposure))
    }

    private fun documentJson(operation: String) =
        """{"v":1,"id":"d","source":"/p/source.jpg","createdAt":0,"updatedAt":0,"operations":[$operation]}"""

    private companion object {
        val SHOT = Shot(id = "a", subjectRef = ImageRef("/p/shot_a.png"), widthPx = 40, heightPx = 30)
        val TIMED_A = SHOT.copy(anchor = NormPoint(0.5f, 1f), placement = ShotPlacement(0.2f, -0.1f, 1.1f, 5f, 0.25f))
        val TIMED_B = SHOT.copy(
            id = "b",
            subjectRef = ImageRef("/p/shot_b.png"),
            anchor = NormPoint(0.4f, 0.95f),
            placement = ShotPlacement(0.1f, -0.05f, 1f, 0f, 0.7f),
        )
        val HERO = HeroMask(ImageRef("/p/shot_h.png"), 40, 30, NormPoint(0.3f, 0.9f))
        val TIMELINE = Timeline(
            listOf("a", "b"),
            TimelineLayout(
                directionDeg = -45f,
                distance = 0.8f,
                strength = 0.6f,
                arrangement = TimelineArrangement.Path,
            ),
            HERO,
        )
    }
}
