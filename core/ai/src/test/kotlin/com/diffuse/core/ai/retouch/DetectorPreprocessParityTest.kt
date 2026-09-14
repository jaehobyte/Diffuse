package com.diffuse.core.ai.retouch

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `work/tasks.md` requirement 5: Python and Android preprocessing are pinned to **one** fixture,
 * not to two descriptions of the same rule.
 *
 * `core/ai/src/test/resources/retouch/preprocess_parity.json` is written by
 * `python3 scripts/retouch/acne_model.py fixture` and asserted against by
 * `scripts/retouch/test_detector.py` as well as by this test. Changing either preprocessor
 * without regenerating the fixture fails on that side; regenerating it without changing this side
 * fails here. Neither can drift quietly, which is the whole point.
 *
 * The tolerances live in the fixture too. They are far tighter than one 8-bit level (1/255 =
 * 0.0039), so agreement here is not "close enough to look right" — it is the same arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
class DetectorPreprocessParityTest {

    @Test
    fun `preprocessing matches the shared fixture`() {
        val fixture = Json.parseToJsonElement(
            checkNotNull(javaClass.getResourceAsStream(FIXTURE)) { "missing $FIXTURE" }
                .bufferedReader()
                .use { it.readText() },
        ).jsonObject
        val size = fixture["model_size"]!!.jsonPrimitive.int()
        val sampleTolerance = fixture["tolerance"]!!.jsonObject["sample_abs"]!!.jsonPrimitive.float()
        val sumTolerance = fixture["tolerance"]!!.jsonObject["sum_rel"]!!.jsonPrimitive.content.toDouble()
        assertEquals(
            LETTERBOX_PAD_VALUE,
            fixture["pad_value"]!!.jsonArray.first().jsonPrimitive.int(),
        )

        for (element in fixture["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val width = case["roi"]!!.jsonArray[0].jsonPrimitive.int()
            val height = case["roi"]!!.jsonArray[1].jsonPrimitive.int()
            val label = "${width}x$height ${case["alpha"]!!.jsonPrimitive.content}"

            val (tensor, transform) = preprocess(
                fixtureRoi(width, height, case["alpha"]!!.jsonPrimitive.content),
                size,
            )

            val box = case["letterbox"]!!.jsonObject
            assertEquals(label, box["scaled"]!!.jsonArray[0].jsonPrimitive.int(), transform.scaledWidth)
            assertEquals(label, box["scaled"]!!.jsonArray[1].jsonPrimitive.int(), transform.scaledHeight)
            assertEquals(label, box["pad_left"]!!.jsonPrimitive.int(), transform.padLeft)
            assertEquals(label, box["pad_top"]!!.jsonPrimitive.int(), transform.padTop)
            assertEquals(label, box["pad_right"]!!.jsonPrimitive.int(), transform.padRight)
            assertEquals(label, box["pad_bottom"]!!.jsonPrimitive.int(), transform.padBottom)
            assertEquals(label, box["scale"]!!.jsonPrimitive.float(), transform.scale, 1e-6f)

            for (sampleElement in case["samples"]!!.jsonArray) {
                val sample = sampleElement.jsonObject
                val index = sample.at("c") * size * size + sample.at("y") * size + sample.at("x")
                assertEquals(
                    "$label sample ${sample["c"]},${sample["y"]},${sample["x"]}",
                    sample["v"]!!.jsonPrimitive.float(),
                    tensor[index],
                    sampleTolerance,
                )
            }

            var sum = 0.0
            for (value in tensor) sum += value.toDouble()
            val expected = case["sum"]!!.jsonPrimitive.content.toDouble()
            assertEquals(label, expected, sum, kotlin.math.abs(expected) * sumTolerance + 1e-9)
        }
    }

    /**
     * The fixture's own generator, restated: ROI pixel `(x, y)` is opaque ARGB with
     * `r = (7x + 13y) % 256`, `g = (3x + 29y) % 256`, `b = (17x + 5y) % 256`, except in the left
     * half of the two alpha cases — fully transparent, or flat white at `a = 128`.
     *
     * Flat white rather than the pattern for the semi-transparent case: Android stores
     * `ARGB_8888` **premultiplied**, so an arbitrary colour does not survive `createBitmap` →
     * `getPixels` exactly. 255 does, which keeps this comparing the compositing arithmetic
     * instead of the platform's storage rounding.
     */
    private fun fixtureRoi(width: Int, height: Int, alpha: String): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val leftHalf = x < width / 2
                pixels[y * width + x] = when {
                    alpha == "left_half_transparent" && leftHalf -> Color.argb(0, 0, 0, 0)
                    alpha == "left_half_semi_transparent" && leftHalf -> Color.argb(128, 255, 255, 255)
                    else -> Color.argb(
                        255,
                        (x * 7 + y * 13) % 256,
                        (x * 3 + y * 29) % 256,
                        (x * 17 + y * 5) % 256,
                    )
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun JsonObject.at(key: String): Int = this[key]!!.jsonPrimitive.int()

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

    private fun kotlinx.serialization.json.JsonPrimitive.float(): Float = content.toFloat()

    private companion object {
        const val FIXTURE = "/retouch/preprocess_parity.json"
    }
}
