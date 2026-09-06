package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/selection_tool.md §8.1: `out = lerp(in, adjusted, maskAlpha)`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskedAdjustTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a masked exposure changes only the masked half`() = runTest {
        val renderer = renderer()
        val plain = document()
        val unmasked = bitmap(renderer.preview(plain, LONG_EDGE))

        val masked = plain
            .withMask(leftHalfMask(unmasked.width, unmasked.height), id = "m")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "m")
        val output = bitmap(renderer.preview(masked, LONG_EDGE))

        GoldenAssert.assertMatchesGolden("exposure_+0.5_masked", output)
    }

    @Test
    fun `the unmasked half is pixel-identical to the input`() = runTest {
        val renderer = renderer()
        val plain = document()
        val unmasked = bitmap(renderer.preview(plain, LONG_EDGE))

        val masked = plain
            .withMask(leftHalfMask(unmasked.width, unmasked.height), id = "m")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "m")
        val output = bitmap(renderer.preview(masked, LONG_EDGE))

        val half = unmasked.width / 2
        for (y in 0 until unmasked.height) {
            for (x in half until unmasked.width) {
                assertEquals(
                    "pixel ($x, $y) outside the mask changed",
                    unmasked.getPixel(x, y),
                    output.getPixel(x, y),
                )
            }
        }
    }

    @Test
    fun `the masked half really did change`() = runTest {
        val renderer = renderer()
        val plain = document()
        val unmasked = bitmap(renderer.preview(plain, LONG_EDGE))

        val masked = plain
            .withMask(leftHalfMask(unmasked.width, unmasked.height), id = "m")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "m")
        val output = bitmap(renderer.preview(masked, LONG_EDGE))

        var changed = 0
        for (y in 0 until unmasked.height) {
            for (x in 0 until unmasked.width / 2) {
                if (unmasked.getPixel(x, y) != output.getPixel(x, y)) changed++
            }
        }
        assertTrue("nothing inside the mask changed", changed > 0)
    }

    @Test
    fun `an adjustment whose mask file is gone still applies, whole-frame`() = runTest {
        val renderer = renderer()
        val plain = document()
        val whole = bitmap(
            renderer.preview(plain.withAdjust(AdjustKind.Exposure, 0.5f), LONG_EDGE),
        )

        val dangling = plain
            .withMask(ImageRef(File(temp.newFolder(), "absent.png").path), id = "m")
            .withAdjust(AdjustKind.Exposure, 0.5f, maskId = "m")
        val output = bitmap(renderer.preview(dangling, LONG_EDGE))

        assertTrue(whole.sameAs(output))
    }

    // ---- fixtures --------------------------------------------------------

    private fun document() = EditDocument(
        id = "doc",
        source = ImageRef(Fixtures.copyTo("photo_512.png", temp.newFolder()).absolutePath),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun leftHalfMask(width: Int, height: Int): ImageRef {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, if (x < width / 2) OPAQUE shl ALPHA_SHIFT else 0)
            }
        }
        val file = File(temp.newFolder(), "mask_m.png")
        MaskIo.write(file, bitmap)
        return ImageRef(file.absolutePath)
    }

    private fun bitmap(result: Result<Bitmap>): Bitmap = when (result) {
        is Result.Success -> result.value
        is Result.Failure -> throw AssertionError("render failed: ${result.error}")
    }

    private fun TestScope.renderer(): CpuRenderer {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val io = dispatcher
        }
        val loader = ImageLoader(
            RuntimeEnvironment.getApplication().contentResolver,
            dispatchers,
        ) { bytes, options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        return CpuRenderer(loader, dispatchers)
    }

    private companion object {
        const val LONG_EDGE = 512
        const val OPAQUE = 255
        const val ALPHA_SHIFT = 24
    }
}
