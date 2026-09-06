package com.diffuse.core.imaging.load

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/edit_model.md: masks are files, so the round trip has to be exact. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskIoTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an ALPHA_8 mask round-trips through the file exactly`() {
        val mask = leftHalf()
        val file = File(temp.newFolder(), "mask_a.png")

        MaskIo.write(file, mask)
        val read = MaskIo.read(file)!!

        assertEquals(Bitmap.Config.ALPHA_8, read.config)
        assertEquals(SIZE, read.width)
        assertEquals(SIZE, read.height)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                assertEquals(
                    "pixel ($x, $y)",
                    alphaAt(mask, x, y),
                    alphaAt(read, x, y),
                )
            }
        }
    }

    @Test
    fun `the mask stays strictly binary`() {
        val file = File(temp.newFolder(), "mask_a.png")
        MaskIo.write(file, leftHalf())

        val read = MaskIo.read(file)!!

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val alpha = alphaAt(read, x, y)
                assertEquals("pixel ($x, $y) is $alpha", true, alpha == 0 || alpha == 255)
            }
        }
    }

    @Test
    fun `write creates the project folder`() {
        val file = File(File(temp.newFolder(), "projects/p1"), "mask_a.png")

        MaskIo.write(file, leftHalf())

        assertEquals(true, file.isFile)
    }

    @Test
    fun `a missing file reads as null rather than throwing`() {
        assertNull(MaskIo.read(File(temp.newFolder(), "absent.png")))
    }

    @Test
    fun `a file that is not an image reads as null`() {
        val file = File(temp.newFolder(), "mask_a.png")
        file.writeText("not a png")

        assertNull(MaskIo.read(file))
    }

    private fun leftHalf(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, if (x < SIZE / 2) 255 shl 24 else 0)
            }
        }
        return bitmap
    }

    private fun alphaAt(bitmap: Bitmap, x: Int, y: Int) = bitmap.getPixel(x, y) ushr 24

    private companion object {
        const val SIZE = 16
    }
}
