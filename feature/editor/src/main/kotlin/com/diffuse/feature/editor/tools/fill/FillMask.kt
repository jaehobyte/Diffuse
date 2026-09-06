package com.diffuse.feature.editor.tools.fill

import android.graphics.Bitmap
import android.graphics.Rect
import com.diffuse.feature.editor.tools.select.MaskOps
import kotlin.math.roundToInt

/**
 * specs/generative_fill.md §2. The mask 채우기 shows the model is the selection's **bounding box
 * with a margin**, not the selection itself.
 *
 * A silhouette is an instruction as much as a region: whitening the exact outline of a chair asks
 * `gemini-2.5-flash-image` to paint the requested thing *into the shape of a chair*, and on the
 * device that is what came back. 지우기 wants the opposite and is right to (T50's margin only
 * widens the same outline) — it is reconstructing what was behind the thing, so the thing's own
 * shape is the useful boundary. A fill is putting something new there, and a new thing has its
 * own shape.
 *
 * The margin exists for the same reason again: an object drawn to the very edge of its box has no
 * room for its own shadow, contact point or perspective, and the model needs somewhere to put them.
 */
object FillMask {

    /** Of the box's own width and height, added to **each** side. */
    const val MARGIN_FRACTION = 0.3f

    /**
     * The tightest [Rect] containing every set pixel of [mask], or null when nothing is set.
     * Exclusive right/bottom, so `width()` and `height()` are pixel counts.
     */
    fun boundingBox(mask: Bitmap): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (!MaskOps.isSet(mask, x, y)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < left) null else Rect(left, top, right + 1, bottom + 1)
    }

    /** [boundingBox] grown by [MARGIN_FRACTION] on each side and clamped to the bitmap. */
    fun paddedBox(mask: Bitmap): Rect? {
        val box = boundingBox(mask) ?: return null
        val padX = (box.width() * MARGIN_FRACTION).roundToInt()
        val padY = (box.height() * MARGIN_FRACTION).roundToInt()
        return Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(mask.width),
            (box.bottom + padY).coerceAtMost(mask.height),
        )
    }

    /**
     * [paddedBox] as a binary `ALPHA_8` mask at [mask]'s size — opaque inside, clear outside.
     *
     * @return null when [mask] has nothing set, which is a missing selection rather than an empty
     * fill: the caller says so instead of sending the model a blank request.
     */
    fun rectangle(mask: Bitmap): Bitmap? {
        val box = paddedBox(mask) ?: return null
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        val opaque = MaskOps.OPAQUE shl ALPHA_SHIFT
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                out.setPixel(x, y, opaque)
            }
        }
        return out
    }

    private const val ALPHA_SHIFT = 24
}
