package com.diffuse.core.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import java.nio.ByteBuffer

/**
 * specs/skin_retouch_pipeline.md §3: the **most** a kind may change on the selected face, in the
 * face ROI's own pixels. It is not a defect mask — finding the blemish, the shine or the shadow
 * inside it is the engine's job (§2, §8.1).
 *
 * Built from the selected face's contours only, and every neighbour a kind has to keep out is cut
 * away with a margin: eyes (with lashes and liner), brows, lips, nostrils and other people's faces.
 * The face rectangle is never taken as skin (skin_retouch.md §3). Margins are initial product
 * values, recorded in `SKIN_MASK_VERSION` so a saved retouch says which rule built its allowance.
 */
@Suppress("TooManyFunctions") // One rasteriser: region rules plus the two primitives they share.
object SkinAllowedMask {

    /** Bumped when a margin or a region rule changes. */
    const val SKIN_MASK_VERSION = 1

    /**
     * @param detail the selected face's geometry, in canonical coordinates.
     * @param otherFaces the other faces in the same analysis; their boxes are excluded (§3 타인).
     * @return one flag per ROI pixel, row-major, `detail.face.roi` sized.
     */
    fun pixels(
        kind: SkinRetouchKind,
        detail: FaceDetail,
        otherFaces: List<DetectedFace> = emptyList(),
    ): BooleanArray {
        val roi = detail.face.roi
        val width = roi.width()
        val height = roi.height()
        val regions = detail.geometry.regions.mapValues { (_, points) -> points.map { it.local(roi) } }
        val mask = BooleanArray(width * height)
        val oval = regions[FaceRegion.FaceOval]?.let { scaled(hull(it), OVAL_SCALE) } ?: return mask

        when (kind) {
            SkinRetouchKind.Blemish, SkinRetouchKind.Shine -> fill(oval, mask, width, height, true)
            SkinRetouchKind.DarkCircles -> underEyes(regions, oval, mask, width, height)
            SkinRetouchKind.ShavingShadow -> lowerFace(regions, oval, mask, width, height)
        }
        excludeFeatures(regions, mask, width, height)
        otherFaces.filter { it.id != detail.face.id }.forEach { other ->
            excludeRect(other.bounds, roi, mask, width, height)
        }
        return mask
    }

    /** [pixels] as the `ALPHA_8` bitmap `SkinRetouchProvider.prepare` takes: 255 = allowed. */
    fun bitmap(
        kind: SkinRetouchKind,
        detail: FaceDetail,
        otherFaces: List<DetectedFace> = emptyList(),
    ): Bitmap {
        val roi = detail.face.roi
        val flags = pixels(kind, detail, otherFaces)
        val width = roi.width()
        val height = roi.height()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val bytes = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) if (flags[y * width + x]) bytes[y * stride + x] = ON
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    }

    /** Below each eye, bounded by the oval, never on the eye itself (skin_retouch.md §1). */
    private fun underEyes(
        regions: Map<FaceRegion, List<PointF>>,
        oval: List<PointF>,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ) {
        val band = BooleanArray(mask.size)
        listOf(FaceRegion.LeftEye, FaceRegion.RightEye).forEach { region ->
            val eye = regions[region] ?: return@forEach
            val left = eye.minOf { it.x }
            val right = eye.maxOf { it.x }
            val bottom = eye.maxOf { it.y }
            val eyeWidth = right - left
            fillEllipse(
                centreX = (left + right) / 2f,
                centreY = bottom + eyeWidth * UNDER_EYE_OFFSET,
                radiusX = eyeWidth * UNDER_EYE_RADIUS_X,
                radiusY = eyeWidth * UNDER_EYE_RADIUS_Y,
                into = band,
                width = width,
                height = height,
            )
        }
        val inside = BooleanArray(mask.size)
        fill(oval, inside, width, height, true)
        for (i in mask.indices) mask[i] = band[i] && inside[i]
    }

    /** Philtrum, chin and jaw: the oval below the nose. Lips go with the feature exclusions. */
    private fun lowerFace(
        regions: Map<FaceRegion, List<PointF>>,
        oval: List<PointF>,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ) {
        val nose = regions[FaceRegion.NoseBottom] ?: return
        val faceHeight = oval.maxOf { it.y } - oval.minOf { it.y }
        val top = nose.maxOf { it.y } + faceHeight * BELOW_NOSE_MARGIN
        fill(oval, mask, width, height, true)
        for (y in 0 until minOf(height, kotlin.math.ceil(top).toInt())) {
            for (x in 0 until width) mask[y * width + x] = false
        }
    }

    private fun excludeFeatures(
        regions: Map<FaceRegion, List<PointF>>,
        mask: BooleanArray,
        width: Int,
        height: Int,
    ) {
        FEATURE_MARGINS.forEach { (group, scale) ->
            val points = group.flatMap { regions[it].orEmpty() }
            if (points.size >= MIN_POLYGON_POINTS) fill(scaled(hull(points), scale), mask, width, height, false)
        }
        regions[FaceRegion.NoseBottom]?.takeIf { it.isNotEmpty() }?.let { nose ->
            val span = (nose.maxOf { it.x } - nose.minOf { it.x }).coerceAtLeast(1f)
            val nostrils = BooleanArray(mask.size)
            fillEllipse(
                centreX = nose.map { it.x }.average().toFloat(),
                centreY = nose.map { it.y }.average().toFloat(),
                radiusX = span * NOSTRIL_RADIUS_X,
                radiusY = span * NOSTRIL_RADIUS_Y,
                into = nostrils,
                width = width,
                height = height,
            )
            for (i in mask.indices) if (nostrils[i]) mask[i] = false
        }
    }

    private fun excludeRect(bounds: Rect, roi: Rect, mask: BooleanArray, width: Int, height: Int) {
        val padX = (bounds.width() * OTHER_FACE_MARGIN).toInt()
        val padY = (bounds.height() * OTHER_FACE_MARGIN).toInt()
        val left = (bounds.left - padX - roi.left).coerceIn(0, width)
        val right = (bounds.right + padX - roi.left).coerceIn(0, width)
        val top = (bounds.top - padY - roi.top).coerceIn(0, height)
        val bottom = (bounds.bottom + padY - roi.top).coerceIn(0, height)
        for (y in top until bottom) for (x in left until right) mask[y * width + x] = false
    }

    private fun PointF.local(roi: Rect) = PointF(x - roi.left, y - roi.top)

    /**
     * Even-odd scanline fill at pixel centres, writing [value]. Pure, so the rule the tests pin is
     * the one that runs — `Canvas` rasterisation does not exist under Robolectric's legacy mode.
     */
    @Suppress("LongParameterList")
    internal fun fill(polygon: List<PointF>, into: BooleanArray, width: Int, height: Int, value: Boolean) {
        if (polygon.size < MIN_POLYGON_POINTS) return
        val crossings = FloatArray(polygon.size)
        val yStart = polygon.minOf { it.y }.toInt().coerceIn(0, height)
        val yEnd = (polygon.maxOf { it.y }.toInt() + 1).coerceIn(0, height)
        for (y in yStart until yEnd) {
            val sampleY = y + HALF
            var count = 0
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                val crosses = (a.y <= sampleY) != (b.y <= sampleY)
                if (crosses) {
                    crossings[count++] = a.x + (sampleY - a.y) / (b.y - a.y) * (b.x - a.x)
                }
            }
            crossings.sort(0, count)
            var c = 0
            while (c + 1 < count) {
                val from = kotlin.math.ceil(crossings[c] - HALF).toInt().coerceIn(0, width)
                val to = kotlin.math.ceil(crossings[c + 1] - HALF).toInt().coerceIn(0, width)
                for (x in from until to) into[y * width + x] = value
                c += 2
            }
        }
    }

    @Suppress("LongParameterList")
    private fun fillEllipse(
        centreX: Float,
        centreY: Float,
        radiusX: Float,
        radiusY: Float,
        into: BooleanArray,
        width: Int,
        height: Int,
    ) {
        if (radiusX <= 0f || radiusY <= 0f) return
        val top = (centreY - radiusY).toInt().coerceIn(0, height)
        val bottom = (centreY + radiusY + 1).toInt().coerceIn(0, height)
        val left = (centreX - radiusX).toInt().coerceIn(0, width)
        val right = (centreX + radiusX + 1).toInt().coerceIn(0, width)
        for (y in top until bottom) {
            for (x in left until right) {
                val dx = (x + HALF - centreX) / radiusX
                val dy = (y + HALF - centreY) / radiusY
                if (dx * dx + dy * dy <= 1f) into[y * width + x] = true
            }
        }
    }

    /** Andrew's monotone chain: ML Kit's split contours (top and bottom lip) as one outline. */
    internal fun hull(points: List<PointF>): List<PointF> {
        val sorted = points.distinctBy { it.x to it.y }.sortedWith(compareBy<PointF> { it.x }.thenBy { it.y })
        if (sorted.size < MIN_POLYGON_POINTS) return sorted
        fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<PointF>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower += p
        }
        val upper = mutableListOf<PointF>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun scaled(polygon: List<PointF>, scale: Float): List<PointF> {
        if (polygon.isEmpty()) return polygon
        val cx = polygon.map { it.x }.average().toFloat()
        val cy = polygon.map { it.y }.average().toFloat()
        return polygon.map { PointF(cx + (it.x - cx) * scale, cy + (it.y - cy) * scale) }
    }

    private const val HALF = 0.5f
    private const val MIN_POLYGON_POINTS = 3
    private val ON = 255.toByte()

    /** Slightly inside the jawline and hairline, where ML Kit's oval meets hair and background. */
    private const val OVAL_SCALE = 0.95f
    private const val OTHER_FACE_MARGIN = 0.1f
    private const val UNDER_EYE_OFFSET = 0.30f
    private const val UNDER_EYE_RADIUS_X = 0.62f
    private const val UNDER_EYE_RADIUS_Y = 0.30f
    private const val BELOW_NOSE_MARGIN = 0.02f
    private const val NOSTRIL_RADIUS_X = 0.75f
    private const val NOSTRIL_RADIUS_Y = 0.45f

    /** Eyes wide enough for lashes and liner; lips wide enough for the lip line. */
    private val FEATURE_MARGINS: List<Pair<List<FaceRegion>, Float>> = listOf(
        listOf(FaceRegion.LeftEye) to 1.8f,
        listOf(FaceRegion.RightEye) to 1.8f,
        listOf(FaceRegion.LeftEyebrow) to 1.4f,
        listOf(FaceRegion.RightEyebrow) to 1.4f,
        listOf(FaceRegion.UpperLip, FaceRegion.LowerLip) to 1.3f,
    )
}
