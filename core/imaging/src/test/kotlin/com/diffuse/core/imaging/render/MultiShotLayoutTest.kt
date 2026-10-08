package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.TimelineLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * specs/multishot.md §4.2, §9: the time layout's arithmetic — profile, slots on the path, the
 * anchor (not the photo's centre) on its slot, determinism, direction, distance, aspect and
 * resolution independence, the clip check and the mask bounds.
 */
@RunWith(AndroidJUnit4::class)
class MultiShotLayoutTest {

    // ---- the afterimage profile --------------------------------------------------------------

    @Test
    fun `one shot is half, more go from a quarter to seventy percent, scaled by strength`() {
        assertEquals(0.5f, MultiShotLayout.opacity(0, 1, 1f), EPS)
        assertList(listOf(0.25f, 0.7f), (0 until 2).map { MultiShotLayout.opacity(it, 2, 1f) })
        assertList(listOf(0.25f, 0.4f, 0.55f, 0.7f), (0 until 4).map { MultiShotLayout.opacity(it, 4, 1f) })
        assertList(listOf(0.125f, 0.2f, 0.275f, 0.35f), (0 until 4).map { MultiShotLayout.opacity(it, 4, 0.5f) })
        assertEquals(0f, MultiShotLayout.opacity(3, 4, 0f), EPS)
    }

    @Test
    fun `faded changes opacity only`() {
        val shots = List(3) { shot("s$it", placement = ShotPlacement(0.1f * it, -0.2f, 1.5f, 30f, 0.9f)) }

        val faded = MultiShotLayout.faded(shots, 1f)

        assertEquals(shots.map { it.placement.copy(opacity = 0f) }, faded.map { it.placement.copy(opacity = 0f) })
        assertList(listOf(0.25f, 0.475f, 0.7f), faded.map { it.placement.opacity })
    }

    // ---- slots on the path -------------------------------------------------------------------

    @Test
    fun `four shots sit at the start and a quarter, half and three quarters of the way`() {
        val hero = NormPoint(0.3f, 0.8f)
        val layout = pathLayout(directionDeg = 135f, distance = 0.5f)

        val targets = (0 until 4).map { MultiShotLayout.target(hero, layout, it, 4, W, H) }

        // The path is half the short side long and runs down-left, so it starts up-right.
        val length = 0.5f * min(W, H)
        targets.forEachIndexed { index, target ->
            val remaining = length * (1f - index / 4f)
            assertEquals(hero.x + remaining / hypot(1f, 1f) / W, target.x, EPS)
            assertEquals(hero.y - remaining / hypot(1f, 1f) / H, target.y, EPS)
        }
        // The last afterimage is a quarter short of the hero, never on top of it.
        assertTrue(targets.last().x > hero.x)
    }

    @Test
    fun `one shot is at the start, and distance zero puts every anchor on the hero's`() {
        val hero = NormPoint(0.5f, 0.5f)
        val single = MultiShotLayout.target(hero, pathLayout(directionDeg = 0f, distance = 0.5f), 0, 1, W, H)
        assertEquals(0.5f - 0.5f * H / W, single.x, EPS)
        assertEquals(0.5f, single.y, EPS)

        (0 until 3).forEach {
            val target = MultiShotLayout.target(hero, pathLayout(distance = 0f), it, 3, W, H)
            assertEquals(CanvasPoint(0.5f, 0.5f), target)
        }
    }

    @Test
    fun `the direction only decides which side the path starts on`() {
        val hero = NormPoint(0.5f, 0.5f)
        fun start(degrees: Float) = MultiShotLayout.target(hero, pathLayout(directionDeg = degrees), 0, 2, W, H)

        assertTrue("moving left starts on the right", start(180f).x > 0.5f)
        assertEquals(0.5f, start(180f).y, EPS)
        assertTrue("moving right starts on the left", start(0f).x < 0.5f)
        assertTrue("down-right starts up-left", start(45f).let { it.x < 0.5f && it.y < 0.5f })
        assertTrue("up-left starts down-right", start(-135f).let { it.x > 0.5f && it.y > 0.5f })
    }

    // ---- left to right (tasks.md 2026-10-01, 위치 배치) ---------------------------------------------

    @Test
    fun `the contract's example - hero at 0_8, start at 0_2, two and four moments`() {
        val hero = NormPoint(0.8f, 0.8f)
        // 0.8 × the short side (300) is 240 px, 0.6 of the 400 px width: S = (0.2, 0.8).
        val layout = pathLayout(directionDeg = 0f, distance = 0.8f)

        val two = (0 until 2).map { MultiShotLayout.target(hero, layout, it, 2, W, H) }
        val four = (0 until 4).map { MultiShotLayout.target(hero, layout, it, 4, W, H) }

        assertList(listOf(0.2f, 0.5f), two.map { it.x })
        assertList(listOf(0.2f, 0.35f, 0.5f, 0.65f), four.map { it.x })
        assertTrue((two + four).all { abs(it.y - 0.8f) < EPS })
    }

    @Test
    fun `placed anchors run oldest to newest along the path for every direction`() {
        val hero = NormPoint(0.7f, 0.75f)
        // Subjects from different places in different photos, sizes and angles.
        val shots = listOf(
            shot("a", 300, 100, ShotPlacement(scale = 1.4f, rotationDeg = 20f), NormPoint(0.1f, 0.9f)),
            shot("b", 90, 400, ShotPlacement(scale = 0.7f, rotationDeg = -60f), NormPoint(0.8f, 1f)),
            shot("c", 64, 64, ShotPlacement(offsetX = 0.3f), NormPoint(0.5f, 0.6f)),
            shot("d", 400, 300, ShotPlacement(scale = 1.81f, rotationDeg = 48.8f), NormPoint(0.9f, 0.4f)),
        )
        listOf(0f, 180f, 45f, 135f, -45f, -135f).forEach { degrees ->
            val layout = pathLayout(directionDeg = degrees, distance = 0.6f)
            val radians = Math.toRadians(degrees.toDouble())
            fun along(x: Float, y: Float) = x * W * cos(radians) + y * H * sin(radians)

            val placed = MultiShotLayout.positioned(shots, hero, layout, W, H)

            val progress = placed.map { MultiShotLayout.onCanvas(it, it.anchor!!, W, H) }.map { along(it.x, it.y) }
            val heroProgress = along(hero.x, hero.y)
            assertTrue("$degrees°: $progress", progress.zipWithNext().all { (a, b) -> a < b })
            assertTrue("$degrees°: newest before the hero", progress.last() < heroProgress)
        }
    }

    @Test
    fun `left to right keeps every anchor on the hero's height`() {
        val hero = NormPoint(0.6f, 0.9f)
        val shots = List(3) {
            shot("s$it", 200 + 50 * it, 150, ShotPlacement(rotationDeg = 15f * it), NormPoint(0.3f, 1f))
        }

        val placed = MultiShotLayout.positioned(shots, hero, pathLayout(directionDeg = 0f), W, H)

        val anchors = placed.map { MultiShotLayout.onCanvas(it, it.anchor!!, W, H) }
        assertTrue(anchors.zipWithNext().all { (a, b) -> a.x < b.x })
        assertTrue(anchors.last().x < hero.x)
        anchors.forEach { assertEquals(hero.y, it.y, EPS) }
    }

    // ---- the anchor on its slot --------------------------------------------------------------

    @Test
    fun `the anchor, not the photo's centre, lands on the slot for any aspect, size and angle`() {
        val hero = NormPoint(0.25f, 0.9f)
        val layout = pathLayout(directionDeg = 135f, distance = 0.6f)
        val shots = listOf(
            shot("wide", 300, 100, ShotPlacement(scale = 1.4f, rotationDeg = 20f), NormPoint(0.2f, 0.95f)),
            shot("tall", 90, 400, ShotPlacement(scale = 0.7f, rotationDeg = -60f), NormPoint(0.5f, 1f)),
            shot("square", 64, 64, ShotPlacement(), NormPoint(0.8f, 0.3f)),
        )

        val placed = MultiShotLayout.positioned(shots, hero, layout, W, H)

        placed.forEachIndexed { index, shot ->
            val target = MultiShotLayout.target(hero, layout, index, shots.size, W, H)
            val anchor = MultiShotLayout.onCanvas(shot, shot.anchor!!, W, H)
            assertEquals("${shot.id} x", target.x, anchor.x, EPS)
            assertEquals("${shot.id} y", target.y, anchor.y, EPS)
            assertEquals("scale is kept", shots[index].placement.scale, shot.placement.scale)
            assertEquals("rotation is kept", shots[index].placement.rotationDeg, shot.placement.rotationDeg)
            assertEquals("opacity is kept", shots[index].placement.opacity, shot.placement.opacity)
        }
    }

    @Test
    fun `laying out again gives the same placements, whatever was moved before`() {
        val hero = NormPoint(0.4f, 0.8f)
        val layout = pathLayout()
        val shots = List(4) { shot("s$it", anchor = NormPoint(0.5f, 1f)) }

        val first = MultiShotLayout.positioned(shots, hero, layout, W, H)
        val moved = first.map { it.copy(placement = it.placement.copy(offsetX = it.placement.offsetX + 0.3f)) }
        val again = MultiShotLayout.positioned(moved, hero, layout, W, H)

        assertEquals(first, again)
        assertEquals(first, MultiShotLayout.positioned(first, hero, layout, W, H))
    }

    @Test
    fun `a reversed order puts the same shots on the opposite slots`() {
        val hero = NormPoint(0.5f, 0.7f)
        val shots = List(3) { shot("s$it", anchor = NormPoint(0.5f, 1f)) }

        val forward = MultiShotLayout.positioned(shots, hero, pathLayout(), W, H).associateBy { it.id }
        val reversed = MultiShotLayout.positioned(shots.reversed(), hero, pathLayout(), W, H).associateBy { it.id }

        assertEquals(forward.getValue("s0").placement, reversed.getValue("s2").placement)
        assertEquals(forward.getValue("s1").placement, reversed.getValue("s1").placement)
    }

    @Test
    fun `a shot without an anchor is left where it is`() {
        val loose = shot("loose", placement = ShotPlacement(offsetX = 0.2f))

        val placed = MultiShotLayout.positioned(listOf(loose), NormPoint(0.5f, 0.5f), pathLayout(), W, H)

        assertEquals(listOf(loose), placed)
    }

    @Test
    fun `preview and export sizes of one canvas give the same normalised placements`() {
        val hero = NormPoint(0.3f, 0.85f)
        val shots = listOf(shot("a", 300, 200, ShotPlacement(scale = 1.2f, rotationDeg = 10f), NormPoint(0.4f, 0.9f)))

        val small = MultiShotLayout.positioned(shots, hero, pathLayout(), W, H).single().placement
        val large = MultiShotLayout.positioned(shots, hero, pathLayout(), W * 10, H * 10).single().placement

        assertEquals(small.offsetX, large.offsetX, EPS)
        assertEquals(small.offsetY, large.offsetY, EPS)
    }

    @Test
    fun `an anchor moved by a drag moves by that drag on the canvas`() {
        val shot = shot("a", 300, 200, ShotPlacement(scale = 1.3f, rotationDeg = 35f), NormPoint(0.5f, 0.9f))
        val before = MultiShotLayout.onCanvas(shot, shot.anchor!!, W, H)

        val moved = MultiShotLayout.anchorMovedBy(shot, shot.anchor!!, 0.02f, -0.03f, W, H)
        val after = MultiShotLayout.onCanvas(shot, moved, W, H)

        assertEquals(before.x + 0.02f, after.x, EPS)
        assertEquals(before.y - 0.03f, after.y, EPS)
    }

    // ---- clip check and bounds ---------------------------------------------------------------

    @Test
    fun `a subject reaching past the canvas is reported as clipped`() {
        val bounds = RectF(0.3f, 0.2f, 0.7f, 1f)
        val inside = shot("in")
        val outside = shot("out", placement = ShotPlacement(offsetX = 0.45f))

        assertFalse(MultiShotLayout.isClipped(inside, bounds, W, H))
        assertTrue(MultiShotLayout.isClipped(outside, bounds, W, H))
    }

    @Test
    fun `bounds count a one-pixel club and the anchor is their bottom centre`() {
        val mask = Bitmap.createBitmap(40, 30, Bitmap.Config.ALPHA_8)
        for (y in 5 until 20) for (x in 10 until 20) mask.setPixel(x, y, Color.BLACK)
        for (y in 18 until 28) mask.setPixel(35, y, Color.BLACK)

        val bounds = MultiShotLayout.bounds(mask)!!

        assertEquals(RectF(10f / 40, 5f / 30, 36f / 40, 28f / 30), bounds)
        assertEquals(NormPoint(23f / 40, 28f / 30), MultiShotLayout.anchorOf(bounds))
        assertNull(MultiShotLayout.bounds(Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8)))
    }

    // ---- 장수별 균등 배치 (§4.3) ------------------------------------------------------------------

    @Test
    fun `the hero takes the middle slot, the left of the two middles for an even count`() {
        assertEquals(1, MultiShotLayout.heroSlot(2))
        assertEquals("six in all: the third slot", 2, MultiShotLayout.heroSlot(5))
        assertEquals(listOf(0, 2), MultiShotLayout.evenSlots(2))
        assertEquals(listOf(0, 1, 3, 4), MultiShotLayout.evenSlots(4))
        assertEquals(listOf(0, 1, 3, 4, 5), MultiShotLayout.evenSlots(5))
        assertEquals(listOf(1), MultiShotLayout.evenSlots(1))
        assertEquals(listOf(0, 2, 3), MultiShotLayout.evenSlots(3))
    }

    @Test
    fun `the contract's examples for three, five and six in all`() {
        val hero = NormPoint(0.5f, 0.8f)
        fun xs(added: Int, spacing: Float = 1f) =
            (0 until added).map { MultiShotLayout.target(hero, even(spacing), it, added, W, H).x }

        assertList(listOf(1f / 6, 5f / 6), xs(2))
        assertList(listOf(0.1f, 0.3f, 0.7f, 0.9f), xs(4))
        assertList(listOf(1f / 6, 1f / 3, 2f / 3, 5f / 6, 1f), xs(5))
        assertList(listOf(0.23333f, 0.36667f, 0.63333f, 0.76667f, 0.9f), xs(5, spacing = 0.8f))
        assertList(List(5) { 0.5f }, xs(5, spacing = 0f))
        // Three and six in all are really different spacings: W / 3 against W / 6.
        assertEquals(1f / 3, xs(2)[1] - 0.5f, EPS)
        assertEquals(1f / 6, xs(5)[3] - xs(5)[2], EPS)
    }

    @Test
    fun `an off-centre hero takes the group with it and nothing is clamped`() {
        val hero = NormPoint(0.3f, 0.7f)

        val xs = (0 until 2).map { MultiShotLayout.target(hero, even(), it, 2, W, H).x }

        assertList(listOf(0.3f - 1f / 3, 0.3f + 1f / 3), xs)
        assertTrue("left of the canvas, not pulled in", xs.first() < 0f)
    }

    @Test
    fun `placed anchors sit on their slots on the hero's height whatever the photo`() {
        val hero = NormPoint(0.45f, 0.82f)
        val shots = listOf(
            shot("a", 300, 100, ShotPlacement(scale = 1.4f, rotationDeg = 20f), NormPoint(0.1f, 0.9f)),
            shot("b", 90, 400, ShotPlacement(scale = 0.7f, rotationDeg = -60f), NormPoint(0.8f, 1f)),
            shot("c", 64, 64, ShotPlacement(offsetX = 0.3f), NormPoint(0.5f, 0.6f)),
            shot("d", 400, 300, ShotPlacement(scale = 1.81f, rotationDeg = 48.8f), NormPoint(0.9f, 0.4f)),
            shot("e", 120, 200, ShotPlacement(offsetY = -0.4f), NormPoint(0.3f, 0.95f)),
        )
        listOf(1f, 0.8f).forEach { spacing ->
            listOf(2, 5).forEach { count ->
                val subset = shots.take(count)
                val placed = MultiShotLayout.positioned(subset, hero, even(spacing), W, H)
                placed.forEachIndexed { index, shot ->
                    val anchor = MultiShotLayout.onCanvas(shot, shot.anchor!!, W, H)
                    val slot = MultiShotLayout.evenSlots(count)[index]
                    val expected = hero.x + (slot - MultiShotLayout.heroSlot(count)) * spacing / (count + 1)
                    assertEquals("$count/$spacing ${shot.id} x", expected, anchor.x, EPS)
                    assertEquals("$count/$spacing ${shot.id} y on the hero's height", hero.y, anchor.y, EPS)
                    assertEquals("scale kept", subset[index].placement.scale, shot.placement.scale)
                    assertEquals("rotation kept", subset[index].placement.rotationDeg, shot.placement.rotationDeg)
                }
                assertEquals("no accumulation", placed, MultiShotLayout.positioned(placed, hero, even(spacing), W, H))
            }
        }
    }

    @Test
    fun `reversing the order swaps which photo takes which slot, the hero's slot stays`() {
        val hero = NormPoint(0.5f, 0.8f)
        val shots = listOf(shot("a", anchor = NormPoint(0.5f, 1f)), shot("b", 60, 40, anchor = NormPoint(0.2f, 1f)))

        val forward = MultiShotLayout.positioned(shots, hero, even(), W, H).associate { it.id to anchorX(it) }
        val reversed = MultiShotLayout.positioned(shots.reversed(), hero, even(), W, H)
            .associate { it.id to anchorX(it) }

        assertEquals(1f / 6, forward.getValue("a"), EPS)
        assertEquals(5f / 6, forward.getValue("b"), EPS)
        assertEquals(1f / 6, reversed.getValue("b"), EPS)
        assertEquals(5f / 6, reversed.getValue("a"), EPS)
    }

    @Test
    fun `preview and export sizes give the same even placements`() {
        val hero = NormPoint(0.4f, 0.85f)
        val shots = listOf(shot("a", 300, 200, ShotPlacement(scale = 1.2f, rotationDeg = 10f), NormPoint(0.4f, 0.9f)))

        val small = MultiShotLayout.positioned(shots, hero, even(0.7f), W, H).single().placement
        val large = MultiShotLayout.positioned(shots, hero, even(0.7f), W * 10, H * 10).single().placement

        assertEquals(small.offsetX, large.offsetX, EPS)
        assertEquals(small.offsetY, large.offsetY, EPS)
    }

    private fun even(spacing: Float = 1f) = TimelineLayout(arrangement = TimelineArrangement.Even, spacing = spacing)

    private fun anchorX(shot: Shot) = MultiShotLayout.onCanvas(shot, shot.anchor!!, W, H).x

    /** §4.2: the earlier path arrangement, which every test above this section is about. */
    private fun pathLayout(directionDeg: Float = 0f, distance: Float = 0.5f) =
        TimelineLayout(directionDeg = directionDeg, distance = distance, arrangement = TimelineArrangement.Path)

    // ---- helpers -----------------------------------------------------------------------------

    private fun shot(
        id: String,
        width: Int = 40,
        height: Int = 30,
        placement: ShotPlacement = ShotPlacement(),
        anchor: NormPoint? = null,
    ) = Shot(id, ImageRef("/p/shot_$id.png"), width, height, placement, anchor)

    private fun assertList(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, EPS) }
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val EPS = 1e-4f
    }
}
