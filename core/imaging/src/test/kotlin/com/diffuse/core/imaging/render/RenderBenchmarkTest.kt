package com.diffuse.core.imaging.render

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.system.measureTimeMillis

/**
 * specs/render.md's render budget, kept out of `check` (specs/testing.md §2) and run by
 * `scripts/bench.sh`, which sets DIFFUSE_BENCHMARK.
 *
 * This runs on the JVM under Robolectric, so the numbers are not the Pixel 6a budget in
 * specs/render.md — it is a regression tripwire, and the printed p50 is the useful part.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderBenchmarkTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun previewFromA4096pxSource() {
        val document = benchDocument()
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, 0.25f)
        measure("2 adjusts", document)
    }

    /**
     * specs/gpu_render.md §1 names this case specifically: `HslOps` evaluates eight band weights
     * per pixel and adjust_hsl.md §5 (D14) refused to fold consecutive HSL adjusts into one pass,
     * so six 혼합 sliders are six full passes over the bitmap. It is the case an AGSL port would
     * help most, and therefore the one that decides whether T66 is worth doing at all.
     */
    @Test
    fun previewWithSixHslAdjusts() {
        val document = benchDocument()
            .withAdjust(AdjustKind.HslRedHue, 0.4f)
            .withAdjust(AdjustKind.HslOrangeSaturation, -0.3f)
            .withAdjust(AdjustKind.HslYellowLuminance, 0.5f)
            .withAdjust(AdjustKind.HslGreenSaturation, 0.35f)
            .withAdjust(AdjustKind.HslBlueHue, -0.45f)
            .withAdjust(AdjustKind.HslMagentaLuminance, 0.2f)
        measure("6 hsl", document)
    }

    private fun benchDocument(): EditDocument {
        assumeTrue(
            "benchmarks run via scripts/bench.sh, not scripts/check.sh",
            System.getenv("DIFFUSE_BENCHMARK") == "true",
        )
        val source = ImageRef(
            Fixtures.copyTo("huge_6000x4000.jpg", temp.newFolder()).absolutePath,
        )
        return EditDocument("bench", source, createdAt = 0L, updatedAt = 0L)
    }

    private fun measure(label: String, document: EditDocument) {
        val dispatchers = object : DispatcherProvider {
            override val default = Dispatchers.Default
            override val io = Dispatchers.IO
        }
        val loader = ImageLoader(
            RuntimeEnvironment.getApplication().contentResolver,
            dispatchers,
        ) { bytes, options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }

        val samples = (1..RUNS).map { run ->
            // A fresh renderer each run, so the caches never serve the measurement.
            val renderer = CpuRenderer(loader, dispatchers)
            measureTimeMillis { runBlocking { renderer.preview(document, TARGET_LONG_EDGE_PX) } }
                .also { println("preview run $run [$label]: ${it}ms") }
        }.sorted()

        println("preview p50 [$label]: ${samples[samples.size / 2]}ms (JVM, not a device budget)")
    }

    private companion object {
        const val RUNS = 5
        const val TARGET_LONG_EDGE_PX = 1080
    }
}
