package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val decodes = AtomicInteger()

    private fun source(): ImageRef =
        ImageRef(Fixtures.copyTo("photo_512.png", temp.newFolder()).absolutePath)

    private fun document(source: ImageRef = source()) = EditDocument(
        id = "doc",
        source = source,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun TestScope.renderer(ops: OpRegistry = RecordingOps()): CpuRenderer {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val io = dispatcher
        }
        val loader = ImageLoader(
            RuntimeEnvironment.getApplication().contentResolver,
            dispatchers,
        ) { bytes, options ->
            if (!options.inJustDecodeBounds) decodes.incrementAndGet()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        return CpuRenderer(loader, dispatchers, ops)
    }

    private fun bitmap(result: Result<Bitmap>): Bitmap {
        assertTrue("expected Success but was $result", result is Result.Success)
        return (result as Result.Success).value
    }

    @Test
    fun `preview renders at the requested long edge`() = runTest {
        val image = bitmap(renderer().preview(document(), targetLongEdgePx = 256))

        assertEquals(256, image.width)
        assertEquals(192, image.height)
    }

    @Test
    fun `full renders at source resolution`() = runTest {
        val image = bitmap(renderer().full(document()))

        assertEquals(512, image.width)
        assertEquals(384, image.height)
    }

    @Test
    fun `an identical preview is served from the cache`() = runTest {
        val renderer = renderer()
        val document = document()

        val first = bitmap(renderer.preview(document, 256))
        val second = bitmap(renderer.preview(document, 256))

        assertSame(first, second)
        assertEquals(1, decodes.get())
    }

    @Test
    fun `two documents with the same operations keep their own previews`() = runTest {
        val renderer = renderer()
        val first = document(source())
        val second = document(
            ImageRef(Fixtures.copyTo("transparent_256.png", temp.newFolder()).absolutePath),
        )

        val a = bitmap(renderer.preview(first, 256))
        val b = bitmap(renderer.preview(second, 256))

        assertNotEquals("the second source must not be served the first one's preview", a, b)
    }

    @Test
    fun `a changed document reuses the cached base decode`() = runTest {
        val renderer = renderer()
        val document = document()

        renderer.preview(document, 256)
        renderer.preview(document.withAdjust(AdjustKind.Exposure, 0.5f), 256)

        assertEquals("the base decode should have been cached", 1, decodes.get())
    }

    @Test
    fun `adjustments run in list order and the crop runs last`() = runTest {
        val ops = RecordingOps()
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withCrop(android.graphics.RectF(0.1f, 0.1f, 0.9f, 0.9f), 15f)
            .withAdjust(AdjustKind.Contrast, 0.25f)

        renderer(ops).preview(document, 256)

        assertEquals(listOf("Exposure=0.5", "Contrast=0.25", "crop@15.0"), ops.applied)
    }

    @Test
    fun `full reports progress once per operation`() = runTest {
        val progress = mutableListOf<Float>()
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, 0.25f)

        renderer().full(document) { progress += it }

        assertEquals(listOf(0.5f, 1f), progress)
    }

    @Test
    fun `a cancelled render stops between operations and publishes nothing`() = runTest {
        var job: Job? = null
        val ops = RecordingOps(onEachOp = { job?.cancel() })
        val renderer = renderer(ops)
        val document = document()
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, 0.25f)
            .withAdjust(AdjustKind.Saturation, 0.25f)
        var thrown: Throwable? = null

        job = launch(start = CoroutineStart.LAZY) {
            try {
                renderer.preview(document, 256)
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        job.start()
        job.join()

        assertNotNull("expected CancellationException", thrown)
        assertEquals("should stop after the first operation", 1, ops.applied.size)
    }

    @Test
    fun `a missing source fails as MissingSource`() = runTest {
        val result = renderer().preview(document(ImageRef("/nope/gone.png")), 256)

        assertEquals(Result.Failure(AppError.MissingSource), result)
    }
}
