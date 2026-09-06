package com.diffuse.core.imaging.load

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * specs/edit_model.md: a mask is an `ALPHA_8` PNG in the project folder.
 *
 * `Bitmap.compress` will not write an `ALPHA_8` bitmap usefully, so the file is an
 * `ARGB_8888` PNG whose alpha carries the mask — lossless, and it round-trips through
 * `BitmapFactory` on every platform level.
 */
object MaskIo {

    private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    private const val TRANSPARENT = 0
    private const val ALPHA_SHIFT = 24
    private const val ALPHA_MAX = 255
    private const val PNG_QUALITY = 100

    fun write(file: File, alpha: Bitmap) {
        file.parentFile?.mkdirs()
        val argb = toArgb(alpha)
        try {
            file.outputStream().use { argb.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        } finally {
            argb.recycle()
        }
    }

    /** @return null when the file is missing or is not a decodable image. */
    fun read(file: File): Bitmap? {
        val decoded = if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        return decoded?.let {
            try {
                toAlpha(it)
            } finally {
                it.recycle()
            }
        }
    }

    private fun toArgb(alpha: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until alpha.height) {
            for (x in 0 until alpha.width) {
                val set = (alpha.getPixel(x, y) ushr ALPHA_SHIFT) != 0
                out.setPixel(x, y, if (set) OPAQUE_WHITE else TRANSPARENT)
            }
        }
        return out
    }

    private fun toAlpha(argb: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(argb.width, argb.height, Bitmap.Config.ALPHA_8)
        for (y in 0 until argb.height) {
            for (x in 0 until argb.width) {
                val set = (argb.getPixel(x, y) ushr ALPHA_SHIFT) != 0
                out.setPixel(x, y, if (set) ALPHA_MAX shl ALPHA_SHIFT else 0)
            }
        }
        return out
    }
}
