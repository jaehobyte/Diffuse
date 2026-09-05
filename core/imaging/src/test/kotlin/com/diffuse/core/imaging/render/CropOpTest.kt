package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.model.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropOpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun crop(rect: RectF, angleDeg: Float) =
        Operation.Crop("crop", rect, angleDeg)

    @Test
    fun `a 1 to 1 crop golden`() {
        // 512x384: a centred square is 384 wide, i.e. 0.75 of the width.
        val square = RectF(0.125f, 0f, 0.875f, 1f)

        val output = Ops.crop(fixture(), crop(square, 0f))

        assertEquals(384, output.width)
        assertEquals(384, output.height)
        GoldenAssert.assertMatchesGolden("crop_1x1", output)
    }

    @Test
    fun `a 15 degree straighten golden`() {
        val rect = RectF(0.2f, 0.2f, 0.8f, 0.8f)

        val output = Ops.crop(fixture(), crop(rect, 15f))

        GoldenAssert.assertMatchesGolden("crop_straighten_15", output)
    }

    @Test
    fun `a full frame unrotated crop returns the same pixels`() {
        val input = fixture()

        val output = Ops.crop(input, crop(RectF(0f, 0f, 1f, 1f), 0f))

        assertEquals(input.width, output.width)
        assertEquals(input.height, output.height)
        assertEquals(input.getPixel(10, 10), output.getPixel(10, 10))
    }

    @Test
    fun `quarter turns swap the canvas and are split out of the angle`() {
        assertEquals(1, CropOp.quarterTurnsOf(105f))
        assertEquals(15f, CropOp.straightenOf(105f), 0.001f)
        assertEquals(2, CropOp.quarterTurnsOf(180f))
        assertEquals(0f, CropOp.straightenOf(180f), 0.001f)
        assertEquals(3, CropOp.quarterTurnsOf(-90f))

        val output = Ops.crop(fixture(), crop(RectF(0f, 0f, 1f, 1f), 90f))

        assertEquals(384, output.width)
        assertEquals(512, output.height)
    }

    @Test
    fun `a straighten never leaves a fully transparent corner inside the rect`() {
        // The crop tool guarantees this; the render must not undo it.
        val rect = RectF(0.3f, 0.3f, 0.7f, 0.7f)

        val output = Ops.crop(fixture(), crop(rect, 15f))

        val corners = listOf(
            output.getPixel(0, 0),
            output.getPixel(output.width - 1, 0),
            output.getPixel(0, output.height - 1),
            output.getPixel(output.width - 1, output.height - 1),
        )
        assertTrue("found a transparent corner: $corners", corners.all { (it ushr 24) == 255 })
    }
}
