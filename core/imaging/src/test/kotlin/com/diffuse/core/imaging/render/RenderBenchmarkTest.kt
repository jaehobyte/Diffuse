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
        assumeTrue(
            "benchmarks run via scripts/bench.sh, not scripts/check.sh",
            System.getenv("DIFFUSE_BENCHMARK") == "true",
        )

        val dispatchers = object : DispatcherProvider {
            override val default = Dispatchers.Default
            override val io = Dispatchers.IO
        }
        val loader = ImageLoader(
            RuntimeEnvironment.getApplication().contentResolver,
            dispatchers,
        ) { bytes, options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        val source = ImageRef(
            Fixtures.copyTo("huge_6000x4000.jpg", temp.newFolder()).absolutePath,
        )
        val document = EditDocument("bench", source, createdAt = 0L, updatedAt = 0L)
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Contrast, 0.25f)

        val samples = (1..RUNS).map { run ->
            // A fresh renderer each run, so the caches never serve the measurement.
            val renderer = CpuRenderer(loader, dispatchers)
            measureTimeMillis { runBlocking { renderer.preview(document, TARGET_LONG_EDGE_PX) } }
                .also { println("preview run $run: ${it}ms") }
        }.sorted()

        println("preview p50: ${samples[samples.size / 2]}ms (JVM, not a device budget)")
    }

    private companion object {
        const val RUNS = 5
        const val TARGET_LONG_EDGE_PX = 1080
    }
}
