package com.diffuse.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * specs/testing.md §4. **These two constants are frozen** — CLAUDE.md lists widening a
 * golden tolerance as a hard limit, so a failing golden means the maths changed, never
 * that the threshold was wrong.
 */
private const val CHANNEL_TOLERANCE = 2
private const val PASSING_PIXEL_FRACTION = 0.999

private const val GOLDEN_RESOURCE_DIR = "src/test/resources/golden"
private const val RECORD_ENV = "DIFFUSE_RECORD_GOLDENS"

object GoldenAssert {

    /**
     * Compares [actual] against `golden/<name>.png`. Recording is deliberately gated on an
     * environment variable rather than being automatic: specs/testing.md §5 requires a
     * missing golden to be a failure, not a silent re-record.
     */
    fun assertMatchesGolden(name: String, actual: Bitmap) {
        val file = goldenFile(name)
        if (System.getenv(RECORD_ENV) == "true") {
            file.parentFile?.mkdirs()
            file.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        check(file.isFile) {
            "missing golden ${file.path}. Record it with $RECORD_ENV=true, then review the image."
        }

        val expected = BitmapFactory.decodeFile(file.path)
            ?: error("golden ${file.path} is not a readable PNG")
        check(expected.width == actual.width && expected.height == actual.height) {
            "size mismatch for $name: golden ${expected.width}x${expected.height}, " +
                "actual ${actual.width}x${actual.height}"
        }

        val total = expected.width * expected.height
        val failed = countFailingPixels(expected, actual)
        val passing = (total - failed).toDouble() / total
        check(passing >= PASSING_PIXEL_FRACTION) {
            "golden $name: only ${"%.4f".format(passing)} of pixels within " +
                "$CHANNEL_TOLERANCE/255 (need $PASSING_PIXEL_FRACTION); $failed of $total differ"
        }
    }

    fun goldenFile(name: String): File = File(repoRoot(), "core/imaging/$GOLDEN_RESOURCE_DIR/$name.png")

    fun goldenDir(): File = File(repoRoot(), "core/imaging/$GOLDEN_RESOURCE_DIR")

    private fun countFailingPixels(expected: Bitmap, actual: Bitmap): Int {
        val width = expected.width
        val height = expected.height
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expected.getPixels(expectedPixels, 0, width, 0, 0, width, height)
        actual.getPixels(actualPixels, 0, width, 0, 0, width, height)

        var failed = 0
        for (index in expectedPixels.indices) {
            if (!withinTolerance(expectedPixels[index], actualPixels[index])) failed++
        }
        return failed
    }

    private fun withinTolerance(expected: Int, actual: Int): Boolean {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            if (kotlin.math.abs(e - a) > CHANNEL_TOLERANCE) return false
        }
        return true
    }

    /** The working directory of a Gradle test task is the module directory. */
    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("repo root not found above ${File("").absolutePath}")
}
