package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.RectF
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.TimelineLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A point on the canvas as a fraction of its width and height; outside 0..1 is off the canvas. */
data class CanvasPoint(val x: Float, val y: Float)

/**
 * specs/multishot.md §4.2. The time layout's arithmetic, pure and local: the same shots, order and
 * settings always give the same placements, and nothing here reads a previous offset — so applying
 * it twice is applying it once. Everything is in fractions of the canvas, so a preview and an
 * export of the same canvas agree. It uses `MultiShotOp.matrix`'s transform (§4) and only solves it
 * for the offset that puts a shot's anchor on its target.
 */
// One small, pure function per question the sheet, the controller and the tests ask.
@Suppress("TooManyFunctions")
object MultiShotLayout {

    private const val HALF = 0.5f
    private const val SINGLE_OPACITY = 0.5f
    private const val FIRST_OPACITY = 0.25f
    private const val LAST_OPACITY = 0.7f
    private const val ALPHA_SHIFT = 24

    /**
     * §4.2: the default afterimage profile, earliest first — one shot 50%, more from 25% to 70%
     * evenly — times [strength].
     */
    fun opacity(index: Int, count: Int, strength: Float): Float {
        val base = if (count <= 1) {
            SINGLE_OPACITY
        } else {
            FIRST_OPACITY + (LAST_OPACITY - FIRST_OPACITY) * index / (count - 1)
        }
        return (base * strength).coerceIn(0f, 1f)
    }

    /**
     * §4.2–§4.3: where the [index]-th of [count] earlier moments (earliest first) goes, by the
     * layout's arrangement. Both keep the hero where it is and never put a moment on its spot.
     */
    // The slot is a function of all six: where, how, which, of how many, on what canvas.
    @Suppress("LongParameterList")
    fun target(
        hero: NormPoint,
        layout: TimelineLayout,
        index: Int,
        count: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): CanvasPoint = when (layout.arrangement) {
        TimelineArrangement.Path -> pathTarget(hero, layout, index, count, canvasWidth, canvasHeight)
        TimelineArrangement.Even -> evenTarget(hero, layout.spacing, evenSlots(count)[index], count + 1)
    }

    /**
     * §4.3: the hero's slot among N = [count] + 1, `k = ⌊(N − 1) / 2⌋` — the middle, or the left of
     * the two middles when N is even.
     */
    fun heroSlot(count: Int): Int = count / 2

    /** §4.3: the slots the earlier moments take, earliest first: 0..N−1 without the hero's. */
    fun evenSlots(count: Int): List<Int> = (0..count).filter { it != heroSlot(count) }

    /**
     * §4.3: slot [slot] of [total]: `x = hx + (slot − k) · spacing / N`, `y = hy`. The group is the
     * screen's N equal columns shifted so the hero's column is on the hero — exact columns only when
     * the hero is at its column's centre. Nothing is clamped: a slot past the edge is cut there.
     */
    fun evenTarget(hero: NormPoint, spacing: Float, slot: Int, total: Int): CanvasPoint =
        CanvasPoint(hero.x + (slot - heroSlot(total - 1)) * spacing / total, hero.y)

    /**
     * §4.2: the [index]-th of [count] earlier moments on the straight path that ends at [hero]. The
     * earliest is at the start, the others at `index / count` of the way; the hero's own slot is
     * the end, so no shot is put on top of it.
     */
    @Suppress("LongParameterList")
    private fun pathTarget(
        hero: NormPoint,
        layout: TimelineLayout,
        index: Int,
        count: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): CanvasPoint {
        val length = layout.distance * min(canvasWidth, canvasHeight)
        val radians = Math.toRadians(layout.directionDeg.toDouble())
        val remaining = length * (1f - index.toFloat() / count)
        return CanvasPoint(
            x = hero.x - (cos(radians) * remaining / canvasWidth).toFloat(),
            y = hero.y - (sin(radians) * remaining / canvasHeight).toFloat(),
        )
    }

    /**
     * §4.2 requirement 21: new positions only. [inTimeOrder] is earliest first; scale, rotation and
     * opacity are kept, and a shot without an anchor is left where it is.
     */
    fun positioned(
        inTimeOrder: List<Shot>,
        hero: NormPoint,
        layout: TimelineLayout,
        canvasWidth: Int,
        canvasHeight: Int,
    ): List<Shot> = inTimeOrder.mapIndexed { index, shot ->
        val anchor = shot.anchor ?: return@mapIndexed shot
        val target = target(hero, layout, index, inTimeOrder.size, canvasWidth, canvasHeight)
        shot.copy(placement = placedAt(shot, anchor, target, canvasWidth, canvasHeight))
    }

    /** §4.2 requirement 21: new opacities only, from the default profile times [strength]. */
    fun faded(inTimeOrder: List<Shot>, strength: Float): List<Shot> = inTimeOrder.mapIndexed { index, shot ->
        shot.copy(placement = shot.placement.copy(opacity = opacity(index, inTimeOrder.size, strength)))
    }

    /** The offset that puts [anchor] (of the photo) on [target], keeping scale and rotation. */
    fun placedAt(
        shot: Shot,
        anchor: NormPoint,
        target: CanvasPoint,
        canvasWidth: Int,
        canvasHeight: Int,
    ): ShotPlacement {
        val (dx, dy) = fromCentre(shot, anchor, canvasWidth, canvasHeight)
        return shot.placement.copy(
            offsetX = target.x - HALF - dx / canvasWidth,
            offsetY = target.y - HALF - dy / canvasHeight,
        )
    }

    /** Where [point] of the photo lands on the canvas under the shot's placement. */
    fun onCanvas(shot: Shot, point: NormPoint, canvasWidth: Int, canvasHeight: Int): CanvasPoint {
        val (dx, dy) = fromCentre(shot, point, canvasWidth, canvasHeight)
        return CanvasPoint(
            x = HALF + shot.placement.offsetX + dx / canvasWidth,
            y = HALF + shot.placement.offsetY + dy / canvasHeight,
        )
    }

    /**
     * §4.2: an anchor moved by a drag of ([dx], [dy]) canvas fractions while the subject stays
     * where it is — the drag taken back through the shot's rotation and scale into the photo.
     */
    @Suppress("LongParameterList") // The drag's two components and the canvas's two sides.
    fun anchorMovedBy(
        shot: Shot,
        anchor: NormPoint,
        dx: Float,
        dy: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ): NormPoint {
        val scale = MultiShotOp.containScale(shot.widthPx, shot.heightPx, canvasWidth, canvasHeight) *
            shot.placement.scale
        val radians = Math.toRadians(-shot.placement.rotationDeg.toDouble())
        val x = dx * canvasWidth
        val y = dy * canvasHeight
        val px = (x * cos(radians) - y * sin(radians)) / scale
        val py = (x * sin(radians) + y * cos(radians)) / scale
        return NormPoint(
            x = (anchor.x + px / shot.widthPx).toFloat().coerceIn(0f, 1f),
            y = (anchor.y + py / shot.heightPx).toFloat().coerceIn(0f, 1f),
        )
    }

    /** §4.2 requirement 18: some part of the subject's bounds ([bounds], of the photo) is off the canvas. */
    fun isClipped(shot: Shot, bounds: RectF, canvasWidth: Int, canvasHeight: Int): Boolean =
        listOf(
            NormPoint(bounds.left, bounds.top),
            NormPoint(bounds.right, bounds.top),
            NormPoint(bounds.left, bounds.bottom),
            NormPoint(bounds.right, bounds.bottom),
        ).any { corner ->
            val point = onCanvas(shot, corner, canvasWidth, canvasHeight)
            point.x < 0f || point.x > 1f || point.y < 0f || point.y > 1f
        }

    /**
     * §4.2 requirement 14: the bounds of every pixel with any alpha, as fractions of [mask] — a
     * one-pixel club or hand counts. Null for an empty mask.
     */
    fun bounds(mask: Bitmap): RectF? {
        val width = mask.width
        val row = IntArray(width)
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until mask.height) {
            mask.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (row[x] ushr ALPHA_SHIFT == 0) continue
                if (top < 0) top = y
                bottom = y
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        if (right < 0) return null
        return RectF(
            left.toFloat() / width,
            top.toFloat() / mask.height,
            (right + 1).toFloat() / width,
            (bottom + 1).toFloat() / mask.height,
        )
    }

    /** §4.2 requirement 14: the initial anchor — the bottom centre of the bounds, not the photo's centre. */
    fun anchorOf(bounds: RectF): NormPoint = NormPoint(bounds.centerX(), bounds.bottom)

    /** The anchor's offset from the photo's centre on the canvas, in canvas pixels: `R · s · (p − c)`. */
    private fun fromCentre(shot: Shot, point: NormPoint, canvasWidth: Int, canvasHeight: Int): Pair<Float, Float> {
        val scale = MultiShotOp.containScale(shot.widthPx, shot.heightPx, canvasWidth, canvasHeight) *
            shot.placement.scale
        val x = (point.x - HALF) * shot.widthPx * scale
        val y = (point.y - HALF) * shot.heightPx * scale
        val radians = Math.toRadians(shot.placement.rotationDeg.toDouble())
        return Pair(
            (x * cos(radians) - y * sin(radians)).toFloat(),
            (x * sin(radians) + y * cos(radians)).toFloat(),
        )
    }
}
