package com.diffuse.core.imaging.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.Fixtures
import com.diffuse.core.imaging.GoldenAssert
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.style.StyleCatalog
import com.diffuse.core.imaging.style.atIntensity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * specs/vibe_edit.md §18: the before/after evidence behind the planner's per-tool starting ranges.
 * Every row is the real CPU registry ([Ops]) applied to `photo_512.png` (derived from kodim23)
 * and three **synthetic** variants of it — darkened, pastel and dark-with-noise. They stand in for
 * backlit, pastel and night photos only as far as tone and noise go; they are not those photos,
 * and nothing here judges a face, a relationship or taste.
 *
 * The assertions are the properties the prompt's guidance rests on. The table and contact sheets
 * are written to `work/evidence/plan_strength/` only with `DIFFUSE_PLAN_EVIDENCE=true`, so an
 * ordinary `check` leaves the tree alone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanStrengthEvidenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val record = System.getenv(RECORD_ENV) == "true"

    private fun photo(): Bitmap {
        val file = Fixtures.copyTo("photo_512.png", temp.newFolder())
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    /** The four inputs, each a name and how it was made. */
    private fun inputs(): List<Pair<String, Bitmap>> {
        val base = photo()
        return listOf(
            "good (photo_512 as is)" to base,
            "dark (exposure -0.7 baked)" to Ops.adjust(AdjustKind.Exposure)(base, -0.7f),
            "pastel (saturation -0.6, exposure +0.3 baked)" to
                Ops.adjust(AdjustKind.Exposure)(Ops.adjust(AdjustKind.Saturation)(base, -0.6f), 0.3f),
            "night (exposure -1.0 baked + noise sd 6)" to noisy(Ops.adjust(AdjustKind.Exposure)(base, -1f)),
        )
    }

    @Test
    fun `adjust rows, measured on the real ops`() {
        val rows = mutableListOf<Row>()
        inputs().forEach { (name, input) ->
            ADJUST_SWEEP.forEach { (kind, values) ->
                values.forEach { value ->
                    rows += measure(name, kind.name, value, input, Ops.adjust(kind)(input, value))
                }
            }
        }
        // What the guidance says: exposure moves lightness fastest; a small value is visible.
        val good = rows.filter { it.input.startsWith("good") }
        val exposure02 = good.single { it.tool == "Exposure" && it.value == 0.2f }
        val saturation02 = good.single { it.tool == "Saturation" && it.value == 0.2f }
        assertTrue("exposure 0.2 must change lightness visibly", exposure02.deltaL > 3.0)
        assertTrue("saturation 0.2 leaves lightness nearly alone", saturation02.deltaL < exposure02.deltaL)
        // Every lift is monotonic in its own value.
        ADJUST_SWEEP.forEach { (kind, _) ->
            val sweep = good.filter { it.tool == kind.name }.map { it.deltaE }
            assertTrue("$kind is monotonic", sweep.zipWithNext().all { (a, b) -> b >= a - EPSILON })
        }
        if (record) write("adjust", rows)
    }

    @Test
    fun `style rows at two intensities, from the catalog`() {
        val presets = StyleCatalog.load(RuntimeEnvironment.getApplication().assets)
        val rows = mutableListOf<Row>()
        inputs().take(2).forEach { (name, input) ->
            presets.forEach { preset ->
                STYLE_INTENSITIES.forEach { intensity ->
                    val values = preset.params.atIntensity(intensity)
                    val out = values.entries.fold(input) { acc, (kind, value) -> Ops.adjust(kind)(acc, value) }
                    rows += measure(name, "style:${preset.id}", intensity.toFloat(), input, out)
                }
            }
        }
        val full = rows.filter { it.value == FULL.toFloat() && it.input.startsWith("good") }
        val half = rows.filter { it.value == HALF.toFloat() && it.input.startsWith("good") }
        // 100 is the preset's whole move and 50 about half of it — never "a natural default".
        full.zip(half).forEach { (f, h) -> assertTrue("${f.tool} half < full", h.deltaE <= f.deltaE + EPSILON) }
        if (record) write("style", rows)
    }

    /** Sharpen raises fine detail, and on the noisy input it raises the noise with it. */
    @Test
    fun `sharpen rows, with noise amplification`() {
        val rows = mutableListOf<Row>()
        inputs().forEach { (name, input) ->
            SHARPEN_VALUES.forEach { value ->
                rows += measure(name, "Sharpen", value, input, Ops.adjust(AdjustKind.Sharpen)(input, value))
            }
        }
        val night = rows.filter { it.input.startsWith("night") }
        assertTrue("sharpen amplifies noise", night.last().detailRatio > 1.0)
        if (record) write("sharpen", rows)
    }

    // ---- measurement ------------------------------------------------------

    private data class Row(
        val input: String,
        val tool: String,
        val value: Float,
        val deltaL: Double,
        val deltaE: Double,
        val deltaChroma: Double,
        val clippedHigh: Double,
        val crushedLow: Double,
        val detailRatio: Double,
        val before: Bitmap,
        val after: Bitmap,
    )

    private fun measure(input: String, tool: String, value: Float, before: Bitmap, after: Bitmap): Row {
        val a = pixels(before)
        val b = pixels(after)
        var dl = 0.0
        var de = 0.0
        var dc = 0.0
        var clipped = 0
        var crushed = 0
        for (i in a.indices) {
            val la = lab(a[i])
            val lb = lab(b[i])
            dl += lb[0] - la[0]
            de += sqrt((lb[0] - la[0]).pow(2) + (lb[1] - la[1]).pow(2) + (lb[2] - la[2]).pow(2))
            dc += sqrt(lb[1] * lb[1] + lb[2] * lb[2]) - sqrt(la[1] * la[1] + la[2] * la[2])
            if (maxChannel(b[i]) >= CLIP && maxChannel(a[i]) < CLIP) clipped++
            if (maxChannel(b[i]) <= CRUSH && maxChannel(a[i]) > CRUSH) crushed++
        }
        val n = a.size.toDouble()
        return Row(
            input, tool, value, dl / n, de / n, dc / n, clipped / n, crushed / n,
            detail(after) / max(detail(before), EPSILON), before, after,
        )
    }

    /** Mean absolute Laplacian of luma: fine detail and noise alike. */
    private fun detail(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val p = pixels(bitmap)
        var sum = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = luma(p[y * w + x]) * 4 - luma(p[y * w + x - 1]) - luma(p[y * w + x + 1]) -
                luma(p[(y - 1) * w + x]) - luma(p[(y + 1) * w + x])
            sum += abs(c)
        }
        return sum / ((w - 2) * (h - 2))
    }

    private fun noisy(bitmap: Bitmap): Bitmap {
        val random = Random(NOISE_SEED)
        val p = pixels(bitmap)
        for (i in p.indices) {
            val n = (random.nextDouble() - 0.5) * 2 * NOISE_AMPLITUDE
            p[i] = Color.rgb(
                (Color.red(p[i]) + n).toInt().coerceIn(0, 255),
                (Color.green(p[i]) + n).toInt().coerceIn(0, 255),
                (Color.blue(p[i]) + n).toInt().coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(p, 0, width, 0, 0, width, height)
        }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun luma(c: Int) = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    private fun maxChannel(c: Int) = maxOf(Color.red(c), Color.green(c), Color.blue(c))

    /** sRGB → CIE L*a*b* (D65). */
    private fun lab(c: Int): DoubleArray {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(Color.red(c))
        val g = lin(Color.green(c))
        val b = lin(Color.blue(c))
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    // ---- output -----------------------------------------------------------

    private fun write(name: String, rows: List<Row>) {
        val dir = File(repoRoot(), OUT_DIR)
            .also { it.mkdirs() }
        File(dir, "$name.md").writeText(
            buildString {
                append("| input | tool | value | ΔL* | ΔE76 | Δchroma | new clip ≥250 | new crush ≤5 | detail ×|\n")
                append("|---|---|---|---|---|---|---|---|---|\n")
                rows.forEach { r ->
                    append(
                        String.format(
                            Locale.ROOT,
                            "| %s | %s | %.2f | %+.1f | %.1f | %+.1f | %.1f%% | %.1f%% | %.2f |\n",
                            r.input, r.tool, r.value, r.deltaL, r.deltaE, r.deltaChroma,
                            r.clippedHigh * PERCENT, r.crushedLow * PERCENT, r.detailRatio,
                        ),
                    )
                }
            },
        )
        rows.groupBy { it.input.substringBefore(' ') }.forEach { (input, group) ->
            sheet(group).let { bitmap ->
                File(dir, "${name}_$input.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    /** `golden/` is `core/imaging/src/test/resources/golden`; six levels up is the repository. */
    private fun repoRoot(): File =
        generateSequence(GoldenAssert.goldenDir()) { it.parentFile }.drop(REPO_DEPTH).first()

    /** One row per tool: the input, then each value, at thumbnail size. */
    private fun sheet(rows: List<Row>): Bitmap {
        val byTool = rows.groupBy { it.tool }
        val columns = 1 + byTool.values.maxOf { it.size }
        val sheet = Bitmap.createBitmap(columns * THUMB, byTool.size * THUMB, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        byTool.values.forEachIndexed { row, group ->
            canvas.drawBitmap(thumb(group.first().before), 0f, (row * THUMB).toFloat(), null)
            group.forEachIndexed { column, r ->
                canvas.drawBitmap(thumb(r.after), ((column + 1) * THUMB).toFloat(), (row * THUMB).toFloat(), null)
            }
        }
        return sheet
    }

    private fun thumb(bitmap: Bitmap) =
        Bitmap.createScaledBitmap(bitmap, THUMB, THUMB * bitmap.height / bitmap.width, true)

    private companion object {
        const val RECORD_ENV = "DIFFUSE_PLAN_EVIDENCE"
        const val OUT_DIR = "work/evidence/plan_strength"
        const val REPO_DEPTH = 6
        const val CLIP = 250
        const val CRUSH = 5
        const val THUMB = 160
        const val PERCENT = 100
        const val EPSILON = 1e-6
        const val NOISE_SEED = 7
        const val NOISE_AMPLITUDE = 10.4 // uniform ±10.4 ≈ sd 6
        const val HALF = 50
        const val FULL = 100
        val STYLE_INTENSITIES = listOf(HALF, FULL)
        val SHARPEN_VALUES = listOf(0.2f, 0.4f, 0.7f)
        val ADJUST_SWEEP = listOf(
            AdjustKind.Exposure to listOf(0.1f, 0.2f, 0.3f, 0.5f),
            AdjustKind.Shadows to listOf(0.2f, 0.4f, 0.6f),
            AdjustKind.Contrast to listOf(0.1f, 0.2f, 0.4f),
            AdjustKind.Saturation to listOf(0.1f, 0.2f, 0.4f),
            AdjustKind.Vibrance to listOf(0.1f, 0.2f, 0.4f),
            AdjustKind.HslRedSaturation to listOf(0.2f, 0.4f, 0.6f),
            AdjustKind.HslBlueSaturation to listOf(0.2f, 0.4f, 0.6f),
        )
    }
}
