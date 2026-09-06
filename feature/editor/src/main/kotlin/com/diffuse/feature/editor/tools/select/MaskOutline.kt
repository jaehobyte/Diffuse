package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath

/**
 * specs/selection_tool.md §5: the scrim is the image rect minus the mask, and the outline is
 * the mask's edge. Both come from the same path, so they can never disagree.
 *
 * Built from row runs rather than by marching squares: `Region` already knows how to union
 * rectangles and hand back a boundary, and a binary mask is exactly a set of runs.
 */
object MaskOutline {

    /** @return a path in **image pixel** coordinates. Empty when nothing is selected. */
    fun pathOf(alpha: Bitmap): Path {
        val region = Region()
        for (y in 0 until alpha.height) {
            var runStart = -1
            for (x in 0 until alpha.width) {
                val set = (alpha.getPixel(x, y) ushr ALPHA_SHIFT) != 0
                if (set && runStart < 0) {
                    runStart = x
                } else if (!set && runStart >= 0) {
                    region.union(Rect(runStart, y, x, y + 1))
                    runStart = -1
                }
            }
            if (runStart >= 0) region.union(Rect(runStart, y, alpha.width, y + 1))
        }
        return region.boundaryPath.asComposePath()
    }

    fun isEmpty(alpha: Bitmap): Boolean {
        for (y in 0 until alpha.height) {
            for (x in 0 until alpha.width) {
                if ((alpha.getPixel(x, y) ushr ALPHA_SHIFT) != 0) return false
            }
        }
        return true
    }

    private const val ALPHA_SHIFT = 24
}
