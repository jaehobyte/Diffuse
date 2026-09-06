package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.load.MaskIo
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/edit_model.md: a `Mask` op changes no pixels, so consumers read it through the renderer. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResolveMaskTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an active mask resolves to its bitmap`() = runTest {
        val ref = writeMask()
        val document = document().withMask(ref, id = "a")

        val mask = renderer().resolveMask(document, "a")!!

        assertEquals(Bitmap.Config.ALPHA_8, mask.config)
        assertEquals(SIZE, mask.width)
        assertEquals(255, mask.getPixel(1, 1) ushr 24)
        assertEquals(0, mask.getPixel(SIZE - 1, 1) ushr 24)
    }

    @Test
    fun `an unknown id resolves to null`() = runTest {
        val document = document().withMask(writeMask(), id = "a")

        assertNull(renderer().resolveMask(document, "b"))
    }

    @Test
    fun `a mask whose file is gone resolves to null rather than throwing`() = runTest {
        val document = document().withMask(ImageRef("/absent/mask_a.png"), id = "a")

        assertNull(renderer().resolveMask(document, "a"))
    }

    @Test
    fun `a resolved mask is cached, so the canvas does not re-read it every frame`() = runTest {
        val document = document().withMask(writeMask(), id = "a")
        val renderer = renderer()

        assertSame(renderer.resolveMask(document, "a"), renderer.resolveMask(document, "a"))
    }

    private fun writeMask(): ImageRef {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                bitmap.setPixel(x, y, if (x < SIZE / 2) 255 shl 24 else 0)
            }
        }
        val file = File(temp.newFolder(), "mask_a.png")
        MaskIo.write(file, bitmap)
        return ImageRef(file.absolutePath)
    }

    private fun document() = EditDocument(
        id = "doc",
        source = ImageRef("/p.jpg"),
        createdAt = 0L,
        updatedAt = 0L,
    )

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
        const val SIZE = 16
    }
}
