package com.diffuse.core.imaging.load

import android.graphics.Bitmap

/**
 * A decoded source image, already bounded and oriented (specs/imaging.md).
 * Everything downstream may assume those normalisations have happened.
 */
data class SourceImage(
    val bitmap: Bitmap,
    /** Bitmap dimensions, after orientation. */
    val widthPx: Int,
    val heightPx: Int,
    /** Dimensions as stored, before downsample and orientation. */
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val mimeType: String,
    val hasAlpha: Boolean,
)
