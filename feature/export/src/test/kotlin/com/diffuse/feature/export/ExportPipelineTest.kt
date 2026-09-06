package com.diffuse.feature.export

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.AppError
import com.diffuse.core.common.Result
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.render.Renderer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPipelineTest {

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private fun renderer(width: Int, height: Int) = object : Renderer {
        override suspend fun preview(document: EditDocument, targetLongEdgePx: Int) =
            Result.Success(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

        override suspend fun full(document: EditDocument, onProgress: (Float) -> Unit): Result<Bitmap> {
            onProgress(1f)
            return Result.Success(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }

        override suspend fun resolveMask(document: EditDocument, maskId: String): Bitmap? = null
    }

    private class RecordingStore(
        private val result: (Bitmap) -> Result<Uri> = { Result.Success(Uri.parse("content://saved")) },
    ) : ImageStore {
        var written: Bitmap? = null
        var format: ExportFormat? = null

        override suspend fun write(bitmap: Bitmap, format: ExportFormat): Result<Uri> {
            written = bitmap
            this.format = format
            return result(bitmap)
        }
    }

    @Test
    fun `a 4 to 5 preset at 1080 writes 864 by 1080`() = runTest {
        // specs/export.md §Tests, the worked example.
        val store = RecordingStore()
        val exporter = Exporter(ExportPipeline(renderer(4000, 3000)), store)

        val outcome = exporter.export(
            document,
            ExportSettings(size = ExportSize.Px1080, preset = ExportPreset.FourFive),
        )

        assertTrue(outcome is Exporter.Outcome.Saved)
        assertEquals(864, store.written!!.width)
        assertEquals(1080, store.written!!.height)
    }

    @Test
    fun `original size never upscales`() = runTest {
        val store = RecordingStore()
        val exporter = Exporter(ExportPipeline(renderer(800, 600)), store)

        exporter.export(document, ExportSettings(size = ExportSize.Original))

        assertEquals(800, store.written!!.width)
        assertEquals(600, store.written!!.height)
    }

    @Test
    fun `a target larger than the render does not upscale either`() = runTest {
        val store = RecordingStore()
        val exporter = Exporter(ExportPipeline(renderer(800, 600)), store)

        exporter.export(document, ExportSettings(size = ExportSize.Px2048))

        assertEquals(800, store.written!!.width)
    }

    @Test
    fun `the 9 to 16 preset crops the long axis`() = runTest {
        val store = RecordingStore()
        val exporter = Exporter(ExportPipeline(renderer(1000, 1000)), store)

        exporter.export(document, ExportSettings(preset = ExportPreset.NineSixteen))

        val written = store.written!!
        assertEquals(563, written.width)
        assertEquals(1000, written.height)
    }

    @Test
    fun `the chosen format reaches the store`() = runTest {
        val store = RecordingStore()

        Exporter(ExportPipeline(renderer(100, 100)), store)
            .export(document, ExportSettings(format = ExportFormat.Png))

        assertEquals(ExportFormat.Png, store.format)
    }

    @Test
    fun `a store failure is reported, not swallowed`() = runTest {
        val store = RecordingStore { Result.Failure(AppError.Io(java.io.IOException("full"))) }

        val outcome = Exporter(ExportPipeline(renderer(100, 100)), store).export(document, ExportSettings())

        assertTrue(outcome is Exporter.Outcome.Failed)
    }

    @Test
    fun `progress reaches the caller`() = runTest {
        val seen = mutableListOf<Float>()

        Exporter(ExportPipeline(renderer(100, 100)), RecordingStore())
            .export(document, ExportSettings()) { seen += it }

        assertEquals(listOf(1f), seen)
    }

    @Test
    fun `settings round-trip through storage`() {
        val store = ExportSettingsStore(ApplicationProvider.getApplicationContext())

        store.save(ExportSettings(format = ExportFormat.Png, size = ExportSize.Px1080))

        val loaded = ExportSettingsStore(ApplicationProvider.getApplicationContext()).load()
        assertEquals(ExportFormat.Png, loaded.format)
        assertEquals(ExportSize.Px1080, loaded.size)
        // The preset is per-export intent, so it always comes back as None.
        assertEquals(ExportPreset.None, loaded.preset)
    }
}
