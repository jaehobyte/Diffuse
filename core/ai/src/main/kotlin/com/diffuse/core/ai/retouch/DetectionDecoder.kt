package com.diffuse.core.ai.retouch

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * `work/tasks.md` requirement 6. Confidence, NMS IoU and the detection cap are explicit settings,
 * and these are the **evaluation starting** values rather than tuned ones: changing them is a
 * recorded decision in `work/retouch_evaluation.md`, not an edit here.
 *
 * [acneClassIds] is an allowlist of the model's own class ids. Treating every class a checkpoint
 * emits as a blemish is the failure requirement 6 names; a class nobody has verified is not one.
 */
data class DetectorSettings(
    val confidence: Float = DEFAULT_CONFIDENCE,
    val nmsIou: Float = DEFAULT_NMS_IOU,
    val maxDetections: Int = DEFAULT_MAX_DETECTIONS,
    val acneClassIds: Set<Int> = setOf(0),
) {
    init {
        require(confidence in 0f..1f) { "confidence must be 0..1, was $confidence" }
        require(nmsIou in 0f..1f) { "nms iou must be 0..1, was $nmsIou" }
        require(maxDetections > 0) { "maxDetections must be positive, was $maxDetections" }
        require(acneClassIds.isNotEmpty()) { "the class allowlist may not be empty" }
    }

    companion object {
        const val DEFAULT_CONFIDENCE = 0.25f
        const val DEFAULT_NMS_IOU = 0.45f
        const val DEFAULT_MAX_DETECTIONS = 100
    }
}

/**
 * What the exported graph actually takes and returns, read off the model and cross-checked
 * against its manifest rather than assumed.
 *
 * [rows] is the size of the output's channel axis: `4 + numClasses` for a YOLOv8 detection head,
 * which has no objectness. A graph that disagrees with its own class count is rejected at
 * construction — the alternative is decoding class scores out of coordinate slots and getting
 * boxes that look plausible and are not.
 */
data class DetectorContract(
    val inputName: String,
    val inputSize: Int,
    val outputName: String,
    val rows: Int,
    val anchors: Int,
    val numClasses: Int,
) {
    init {
        require(inputSize > 0) { "input size must be positive, was $inputSize" }
        require(anchors > 0) { "anchor count must be positive, was $anchors" }
        require(numClasses > 0) { "class count must be positive, was $numClasses" }
        require(rows == BOX_ROWS + numClasses) {
            "output has $rows rows, which is not 4 + nc for nc=$numClasses. This decoder only " +
                "knows the box+class layout; another head needs its own decoder."
        }
    }
}

/**
 * Raw graph output to ROI-space detections. Deterministic, and total on malformed input.
 *
 * No objectness is multiplied in (this head has none), no sigmoid is applied (the graph already
 * applied it) and NMS runs exactly once (the export embeds none) — the three ways `work/tasks.md`
 * requirement 6 says a YOLO decoder is usually silently wrong.
 *
 * @param raw the flattened `[1, rows, anchors]` tensor, row-major: anchor `a` of row `r` is at
 * `r * anchors + a`.
 * @return detections in ROI pixels, descending by confidence, and whether the cap was reached.
 */
internal fun decodeDetections(
    raw: FloatArray,
    contract: DetectorContract,
    transform: LetterboxTransform,
    settings: DetectorSettings,
): Pair<List<BlemishDetection>, Boolean> {
    require(raw.size == contract.rows * contract.anchors) {
        "output is ${raw.size} values, the contract says ${contract.rows * contract.anchors}"
    }
    val classes = settings.acneClassIds.filter { it in 0 until contract.numClasses }.sorted()
    require(classes.isNotEmpty()) {
        "no class in ${settings.acneClassIds} exists in a model with ${contract.numClasses}"
    }

    val candidates = ArrayList<Candidate>()
    for (classId in classes) {
        collectClass(raw, contract.anchors, transform, settings, classId, candidates)
    }
    if (candidates.isEmpty()) return emptyList<BlemishDetection>() to false

    // Descending score; the class then the anchor index breaks ties. A total order, so two equal
    // scores cannot swap places between runs and make the cap non-deterministic.
    candidates.sortWith(
        compareByDescending<Candidate> { it.score }.thenBy { it.classId }.thenBy { it.anchor },
    )
    return suppress(candidates, settings)
}

/** Every anchor of one class that clears the threshold and produces a usable box. */
@Suppress("LongParameterList")
private fun collectClass(
    raw: FloatArray,
    anchors: Int,
    transform: LetterboxTransform,
    settings: DetectorSettings,
    classId: Int,
    into: MutableList<Candidate>,
) {
    val scoreRow = (BOX_ROWS + classId) * anchors
    for (anchor in 0 until anchors) {
        val score = raw[scoreRow + anchor]
        if (score.isFinite() && score >= settings.confidence) {
            val box = boxAt(raw, anchor, anchors, transform)
            if (box != null) into += Candidate(box, score, classId, anchor)
        }
    }
}

/** Greedy, class-aware NMS over the already-sorted candidates. */
private fun suppress(
    candidates: List<Candidate>,
    settings: DetectorSettings,
): Pair<List<BlemishDetection>, Boolean> {
    val kept = ArrayList<Candidate>()
    var hitLimit = false
    for (candidate in candidates) {
        if (kept.size >= settings.maxDetections) {
            hitLimit = true
            break
        }
        // One class never suppresses another: two different findings at one place are two
        // findings, not a duplicate.
        val suppressed = kept.any {
            it.classId == candidate.classId && iou(it.box, candidate.box) > settings.nmsIou
        }
        if (!suppressed) kept += candidate
    }
    return kept.map { BlemishDetection(it.box, it.score, it.classId) } to hitLimit
}

private class Candidate(
    val box: RectF,
    val score: Float,
    val classId: Int,
    val anchor: Int,
)

/**
 * One anchor's box, from model space to ROI space: undo the padding, undo the scale, clip, then
 * check it survived.
 *
 * `null` when the tensor holds NaN or Inf — which would win every comparison in NMS and clip to a
 * box covering the whole face — or when the box lay entirely in the letterbox padding, or clipped
 * down to nothing. Keeping either would put a candidate defect mask on pixels that were never
 * part of the photograph.
 */
private fun boxAt(
    raw: FloatArray,
    anchor: Int,
    anchors: Int,
    transform: LetterboxTransform,
): RectF? {
    val centreX = raw[anchor]
    val centreY = raw[anchors + anchor]
    val width = raw[WIDTH_ROW * anchors + anchor]
    val height = raw[HEIGHT_ROW * anchors + anchor]
    if (!allFinite(centreX, centreY, width, height)) return null
    return clipToRoi(centreX, centreY, width, height, transform)
}

private fun allFinite(vararg values: Float): Boolean = values.all { it.isFinite() }

/** Undo the padding and the scale, order the edges, clip, and drop what is left of nothing. */
@Suppress("LongParameterList")
private fun clipToRoi(
    centreX: Float,
    centreY: Float,
    width: Float,
    height: Float,
    transform: LetterboxTransform,
): RectF? {
    val halfWidth = width / HALVES
    val halfHeight = height / HALVES
    var left = (centreX - halfWidth - transform.padLeft) / transform.scale
    var right = (centreX + halfWidth - transform.padLeft) / transform.scale
    var top = (centreY - halfHeight - transform.padTop) / transform.scale
    var bottom = (centreY + halfHeight - transform.padTop) / transform.scale
    if (right < left) { val swap = left; left = right; right = swap }
    if (bottom < top) { val swap = top; top = bottom; bottom = swap }
    left = left.coerceIn(0f, transform.roiWidth.toFloat())
    right = right.coerceIn(0f, transform.roiWidth.toFloat())
    top = top.coerceIn(0f, transform.roiHeight.toFloat())
    bottom = bottom.coerceIn(0f, transform.roiHeight.toFloat())
    if (right - left <= 0f || bottom - top <= 0f) return null
    return RectF(left, top, right, bottom)
}

/** The output's first four rows are the box; the class scores follow. There is no objectness. */
private const val BOX_ROWS = 4
private const val WIDTH_ROW = 2
private const val HEIGHT_ROW = 3
private const val HALVES = 2f

private fun iou(a: RectF, b: RectF): Float {
    val width = min(a.right, b.right) - max(a.left, b.left)
    val height = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (width <= 0f || height <= 0f) return 0f
    val overlap = width * height
    val union = a.width() * a.height() + b.width() * b.height() - overlap
    return if (union > 0f) overlap / union else 0f
}
