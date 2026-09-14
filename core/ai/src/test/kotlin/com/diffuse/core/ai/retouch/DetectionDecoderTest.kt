package com.diffuse.core.ai.retouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/skin_retouch_validation.md §4, `work/tasks.md` requirement 6.
 *
 * Every case here is also a `scripts/retouch/test_detector.py` case. What is checked is that the
 * decoder reads the tensor the manifest describes and nothing else: no objectness multiplied in,
 * no second sigmoid, NMS exactly once, an allowlist rather than "every class is acne", and a
 * malformed tensor reported instead of turned into a box.
 */
@RunWith(RobolectricTestRunner::class)
class DetectionDecoderTest {

    @Test
    fun `a box is mapped back through the letterbox into roi pixels`() {
        val transform = letterboxTransform(320, 160, SIZE) // scale 2, 160px padding top and bottom
        val raw = rawOutput(Anchor(200f, 160f + 100f, 40f, 20f, 0.9f))

        val (detections, _) = decode(raw, transform)

        val box = detections.single().box
        assertEquals(90f, box.left, 1e-3f)
        assertEquals(45f, box.top, 1e-3f)
        assertEquals(110f, box.right, 1e-3f)
        assertEquals(55f, box.bottom, 1e-3f)
        assertEquals(0.9f, detections.single().confidence, 1e-6f)
        assertEquals(0, detections.single().classId)
    }

    @Test
    fun `a below threshold anchor is not a detection`() {
        val (detections, _) = decode(rawOutput(Anchor(100f, 100f, 10f, 10f, 0.24f)), square())

        assertTrue(detections.isEmpty())
    }

    /** specs/skin_retouch_pipeline.md §2: nothing found is a success, told apart from a failure. */
    @Test
    fun `no detection is a normal empty result`() {
        val (detections, hitLimit) = decode(FloatArray(5 * 4), square())

        assertTrue(detections.isEmpty())
        assertFalse(hitLimit)
    }

    @Test
    fun `overlapping boxes of one class are suppressed to the higher score`() {
        val raw = rawOutput(
            Anchor(100f, 100f, 40f, 40f, 0.9f),
            Anchor(104f, 104f, 40f, 40f, 0.8f),
        )

        val (detections, _) = decode(raw, square())

        assertEquals(1, detections.size)
        assertEquals(0.9f, detections.single().confidence, 1e-6f)
    }

    @Test
    fun `boxes far enough apart both survive`() {
        val raw = rawOutput(
            Anchor(100f, 100f, 20f, 20f, 0.9f),
            Anchor(400f, 400f, 20f, 20f, 0.8f),
        )

        assertEquals(2, decode(raw, square()).first.size)
    }

    /**
     * Three boxes 15px apart have an IoU of 0.14, below the 0.45 threshold, so all three stay. A
     * second round of suppression — or one already inside the graph — would drop them.
     */
    @Test
    fun `nms is applied once and only here`() {
        val raw = rawOutput(
            Anchor(100f, 100f, 20f, 20f, 0.9f),
            Anchor(115f, 100f, 20f, 20f, 0.7f),
            Anchor(130f, 100f, 20f, 20f, 0.5f),
        )

        assertEquals(3, decode(raw, square()).first.size)
    }

    @Test
    fun `equal scores are ordered deterministically`() {
        val raw = rawOutput(
            Anchor(400f, 400f, 20f, 20f, 0.5f),
            Anchor(100f, 100f, 20f, 20f, 0.5f),
        )

        val first = decode(raw, square()).first
        val second = decode(raw, square()).first

        assertEquals(first.map { it.box }, second.map { it.box })
        // The tie breaks on anchor index, so anchor 0 stays first however the scores compare.
        assertEquals(390f, first.first().box.left, 1e-3f)
    }

    @Test
    fun `the detection cap is honoured and reported`() {
        val raw = rawOutput(*(0 until 10).map { Anchor(20f + 30 * it, 20f, 10f, 10f, 0.9f) }.toTypedArray())

        val (detections, hitLimit) = decode(raw, square(), settings(maxDetections = 3))

        assertEquals(3, detections.size)
        assertTrue(hitLimit)
    }

    /**
     * requirement 6: an unverified class is not a blemish. The checkpoint declares
     * `names = {0: 'acne'}`, so the allowlist is `{0}` and asking for a class the model does not
     * have is a configuration error rather than a quiet empty answer.
     */
    @Test
    fun `a class outside the model is a configuration error, not an empty result`() {
        val raw = rawOutput(Anchor(100f, 100f, 20f, 20f, 0.9f))

        assertThrows(IllegalArgumentException::class.java) {
            decode(raw, square(), settings(classIds = setOf(1)))
        }
    }

    @Test
    fun `only allowlisted classes are decoded`() {
        val raw = FloatArray(6 * 2)
        // anchor 0: class 0 fires; anchor 1: class 1 fires.
        raw[0] = 100f; raw[2] = 100f; raw[4] = 20f; raw[6] = 20f; raw[8] = 0.9f
        raw[1] = 300f; raw[3] = 300f; raw[5] = 20f; raw[7] = 20f; raw[11] = 0.9f
        val contract = DetectorContract("images", SIZE, "output0", rows = 6, anchors = 2, numClasses = 2)

        val (detections, _) = decodeDetections(raw, contract, square(), DetectorSettings(acneClassIds = setOf(0)))

        assertEquals(1, detections.size)
        assertEquals(0, detections.single().classId)
    }

    @Test
    fun `a box that lies entirely in the padding is dropped`() {
        val transform = letterboxTransform(640, 320, SIZE) // 160px of padding top and bottom
        val raw = rawOutput(Anchor(320f, 40f, 20f, 20f, 0.9f))

        assertTrue(decode(raw, transform).first.isEmpty())
    }

    @Test
    fun `a box hanging over the edge is clipped to the roi`() {
        val raw = rawOutput(Anchor(10f, 10f, 60f, 60f, 0.9f))

        val box = decode(raw, square()).first.single().box

        assertEquals(0f, box.left, 0f)
        assertEquals(0f, box.top, 0f)
        assertEquals(40f, box.right, 1e-3f)
    }

    @Test
    fun `a degenerate box is dropped`() {
        assertTrue(decode(rawOutput(Anchor(100f, 100f, 0f, 20f, 0.9f)), square()).first.isEmpty())
    }

    @Test
    fun `nan and infinite anchors are dropped rather than masking the whole face`() {
        val raw = rawOutput(
            Anchor(Float.NaN, 100f, 20f, 20f, 0.99f),
            Anchor(Float.POSITIVE_INFINITY, 100f, 20f, 20f, 0.99f),
            Anchor(100f, 100f, 20f, 20f, Float.NaN),
            Anchor(300f, 300f, 20f, 20f, 0.9f),
        )

        val (detections, _) = decode(raw, square())

        assertEquals(1, detections.size)
        assertEquals(290f, detections.single().box.left, 1e-3f)
    }

    @Test
    fun `an output of the wrong length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { decode(FloatArray(17), square()) }
    }

    @Test
    fun `a head that is not four plus nc is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectorContract("images", SIZE, "output0", rows = 6, anchors = 8400, numClasses = 1)
        }
    }

    /**
     * The whole path on one odd shape: put a box at the model-space position of a known ROI
     * rectangle in a 301x173 ROI, and get that rectangle back.
     */
    @Test
    fun `a non square roi round trips through the letterbox`() {
        val transform = letterboxTransform(301, 173, SIZE)
        val left = 50f
        val top = 30f
        val right = 90f
        val bottom = 70f
        val raw = rawOutput(
            Anchor(
                (left + right) / 2 * transform.scale + transform.padLeft,
                (top + bottom) / 2 * transform.scale + transform.padTop,
                (right - left) * transform.scale,
                (bottom - top) * transform.scale,
                0.9f,
            ),
        )

        val box = decode(raw, transform).first.single().box

        assertEquals(left, box.left, 1e-2f)
        assertEquals(top, box.top, 1e-2f)
        assertEquals(right, box.right, 1e-2f)
        assertEquals(bottom, box.bottom, 1e-2f)
    }

    private class Anchor(
        val centreX: Float,
        val centreY: Float,
        val width: Float,
        val height: Float,
        val score: Float,
    )

    /** `[1, 5, anchors]` flattened row-major, one anchor per argument. */
    private fun rawOutput(vararg anchors: Anchor): FloatArray {
        val count = anchors.size
        val raw = FloatArray(5 * count)
        anchors.forEachIndexed { index, anchor ->
            raw[index] = anchor.centreX
            raw[count + index] = anchor.centreY
            raw[2 * count + index] = anchor.width
            raw[3 * count + index] = anchor.height
            raw[4 * count + index] = anchor.score
        }
        return raw
    }

    private fun square() = letterboxTransform(SIZE, SIZE, SIZE)

    private fun settings(
        maxDetections: Int = DetectorSettings.DEFAULT_MAX_DETECTIONS,
        classIds: Set<Int> = setOf(0),
    ) = DetectorSettings(maxDetections = maxDetections, acneClassIds = classIds)

    private fun decode(
        raw: FloatArray,
        transform: LetterboxTransform,
        settings: DetectorSettings = DetectorSettings(),
    ): Pair<List<BlemishDetection>, Boolean> {
        val anchors = raw.size / 5
        val contract = DetectorContract(
            inputName = "images",
            inputSize = SIZE,
            outputName = "output0",
            rows = 5,
            anchors = if (anchors > 0) anchors else 1,
            numClasses = 1,
        )
        return decodeDetections(raw, contract, transform, settings)
    }

    private companion object {
        const val SIZE = 640
    }
}
