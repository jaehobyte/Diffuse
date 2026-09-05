package com.diffuse.core.imaging.load

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** specs/architecture.md §5.4: dispatchers are injected so tests can substitute. */
    private fun TestScope.loader(
        decode: ((ByteArray, BitmapFactory.Options) -> android.graphics.Bitmap?)? = null,
    ): ImageLoader {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val io = dispatcher
        }
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        return if (decode == null) {
            ImageLoader(resolver, dispatchers)
        } else {
            ImageLoader(resolver, dispatchers, decode)
        }
    }

    private fun uriOf(fixture: String): Uri =
        Uri.fromFile(Fixtures.copyTo(fixture, temp.newFolder()))

    private fun success(result: Result<SourceImage>): SourceImage {
        assertTrue("expected Success but was $result", result is Result.Success)
        return (result as Result.Success).value
    }

    @Test
    fun `an oversized image is downsampled to a 4096px long edge`() = runTest {
        val image = success(loader().load(uriOf("huge_6000x4000.jpg")))

        assertEquals(4096, image.widthPx)
        assertEquals(4096, image.bitmap.width)
        // 6000x4000 scaled to a 4096 long edge keeps the 3:2 aspect ratio.
        assertEquals(2731, image.heightPx)
        assertEquals(6000, image.sourceWidthPx)
        assertEquals(4000, image.sourceHeightPx)
    }

    @Test
    fun `an image under the bound is not upscaled`() = runTest {
        val image = success(loader().load(uriOf("photo_512.png")))

        assertEquals(512, image.widthPx)
        assertEquals(384, image.heightPx)
        assertEquals(512, image.sourceWidthPx)
    }

    @Test
    fun `exif orientation is applied to pixels, not carried as metadata`() = runTest {
        val file = Fixtures.copyTo("photo_12mp.jpg", temp.newFolder())
        val image = success(loader().load(Uri.fromFile(file)))

        // Stored as 4000x3000 with orientation 6 (rotate 90 CW), so the result is portrait.
        assertEquals(4000, image.sourceWidthPx)
        assertEquals(3000, image.sourceHeightPx)
        assertEquals(3000, image.widthPx)
        assertEquals(4000, image.heightPx)
        // The bitmap itself must be rotated, not merely described as rotated.
        assertEquals(3000, image.bitmap.width)
        assertEquals(4000, image.bitmap.height)

        // Rotating 90 CW maps src(0, h-1) to dst(0, 0).
        val raw = BitmapFactory.decodeFile(file.absolutePath)
        assertPixelsClose(raw.getPixel(0, raw.height - 1), image.bitmap.getPixel(0, 0))
    }

    @Test
    fun `alpha is preserved`() = runTest {
        val image = success(loader().load(uriOf("transparent_256.png")))

        assertTrue("expected an alpha channel", image.hasAlpha)
        assertTrue("expected the bitmap to keep alpha", image.bitmap.hasAlpha())
        // The fixture's top-right quadrant is fully transparent.
        assertEquals(0, image.bitmap.getPixel(200, 20) ushr 24)
    }

    @Test
    fun `a corrupt file fails as Unsupported instead of throwing`() = runTest {
        assertEquals(
            Result.Failure(AppError.Unsupported),
            loader().load(uriOf("corrupt.jpg")),
        )
    }

    @Test
    fun `a missing file fails as MissingSource`() = runTest {
        val missing = Uri.fromFile(File(temp.newFolder(), "gone.jpg"))

        assertEquals(Result.Failure(AppError.MissingSource), loader().load(missing))
    }

    @Test
    fun `an OutOfMemoryError during decode is reported as TooLarge`() = runTest {
        val oomLoader = loader { _, _ -> throw OutOfMemoryError("injected") }

        assertEquals(
            Result.Failure(AppError.TooLarge),
            oomLoader.load(uriOf("photo_512.png")),
        )
    }

    @Test
    fun `mime type comes from the decoded bounds`() = runTest {
        assertEquals("image/png", success(loader().load(uriOf("photo_512.png"))).mimeType)
        assertEquals("image/jpeg", success(loader().load(uriOf("photo_12mp.jpg"))).mimeType)
    }

    private fun assertPixelsClose(expected: Int, actual: Int) {
        val tolerance = 4
        listOf(16, 8, 0).forEach { shift ->
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(
                "channel at shift $shift differs: expected $e, was $a",
                abs(e - a) <= tolerance,
            )
        }
        assertNotEquals(0, expected ushr 24)
    }
}
