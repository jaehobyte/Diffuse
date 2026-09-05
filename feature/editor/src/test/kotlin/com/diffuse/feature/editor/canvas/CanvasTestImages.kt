package com.diffuse.feature.editor.canvas

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Deterministic stand-ins for a preview bitmap; the canvas is what is under test. */
internal fun testImage(
    width: Int = 400,
    height: Int = 300,
    transparentQuadrant: Boolean = false,
): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val quadrants = intArrayOf(
        0xFFE60023.toInt(), // accent red
        0xFF1E7A46.toInt(), // success green
        0xFFB8741A.toInt(), // warning amber
        0xFF3355EE.toInt(), // blue
    )
    for (y in 0 until height) {
        for (x in 0 until width) {
            val quadrant = (if (x < width / 2) 0 else 1) + (if (y < height / 2) 0 else 2)
            val transparent = transparentQuadrant && quadrant == 1
            bitmap.setPixel(x, y, if (transparent) 0x00000000 else quadrants[quadrant])
        }
    }
    return bitmap.asImageBitmap()
}
