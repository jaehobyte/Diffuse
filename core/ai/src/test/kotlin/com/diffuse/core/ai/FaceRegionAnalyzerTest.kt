package com.diffuse.core.ai

import android.graphics.PointF
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * specs/skin_retouch_pipeline.md §3, specs/skin_retouch_validation.md §4 (Analyzer).
 *
 * ML Kit needs Play services, which no JVM test has, so what is tested here is every part of the
 * analyzer that decides — ROI geometry, selection order, per-kind support, the re-detection
 * identity check — and not the part that detects.
 */
@RunWith(RobolectricTestRunner::class)
class FaceRegionAnalyzerTest {

    @Test
    fun `the roi adds a fifth of each axis around the face`() {
        val bounds = Rect(500, 400, 900, 800)

        val roi = roiFor(bounds, CANVAS, CANVAS)

        assertEquals(Rect(420, 320, 980, 880), roi)
    }

    @Test
    fun `a face at the canvas edge has its context clipped, not shifted`() {
        val bounds = Rect(0, 0, 400, 400)

        val roi = roiFor(bounds, CANVAS, CANVAS)

        assertEquals(Rect(0, 0, 480, 480), roi)
    }

    @Test
    fun `the largest face is the first candidate`() {
        val small = face("small", Rect(0, 0, 200, 200))
        val large = face("large", Rect(600, 600, 1000, 1000))

        assertEquals(listOf(large, small), orderedBySelection(listOf(small, large)))
    }

    /** specs/skin_retouch.md §3: ties break upwards first, then leftwards. */
    @Test
    fun `faces of equal size are ordered top before left`() {
        val left = face("left", Rect(0, 300, 300, 600))
        val top = face("top", Rect(600, 0, 900, 300))

        assertEquals(listOf(top, left), orderedBySelection(listOf(left, top)))
    }

    @Test
    fun `faces of equal size on the same row are ordered leftmost first`() {
        val right = face("right", Rect(600, 0, 900, 300))
        val left = face("left", Rect(0, 0, 300, 300))

        assertEquals(listOf(left, right), orderedBySelection(listOf(right, left)))
    }

    @Test
    fun `a frontal face with every contour supports all four kinds`() {
        val support = supportOf(bounds = squareFace(MIN_FACE_PX), geometry = frontal())

        assertEquals(SkinRetouchKind.entries.toSet(), support.filterValues { it is KindSupport.Supported }.keys)
    }

    @Test
    fun `a face under two hundred pixels supports nothing`() {
        val support = supportOf(bounds = squareFace(MIN_FACE_PX - 1), geometry = frontal())

        assertEquals(
            SkinRetouchKind.entries.associateWith { KindSupport.Unsupported(UnsupportedReason.FaceTooSmall) },
            support,
        )
    }

    /** A face may be wide enough and still be too short; the short axis is what governs. */
    @Test
    fun `the shorter axis is what decides the size bar`() {
        val wideButShort = Rect(0, 0, MIN_FACE_PX * 2, MIN_FACE_PX - 1)

        val support = supportOf(bounds = wideButShort, geometry = frontal())

        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.FaceTooSmall),
            support.getValue(SkinRetouchKind.Blemish),
        )
    }

    @Test
    fun `a face yawed past thirty degrees supports nothing`() {
        val support = supportOf(squareFace(MIN_FACE_PX), frontal().copy(yawDegrees = -(MAX_HEAD_ANGLE_DEG + 1f)))

        assertEquals(
            SkinRetouchKind.entries.associateWith { KindSupport.Unsupported(UnsupportedReason.ExtremeAngle) },
            support,
        )
    }

    @Test
    fun `a face rolled past thirty degrees supports nothing`() {
        val support = supportOf(squareFace(MIN_FACE_PX), frontal().copy(rollDegrees = MAX_HEAD_ANGLE_DEG + 1f))

        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.ExtremeAngle),
            support.getValue(SkinRetouchKind.DarkCircles),
        )
    }

    /**
     * The per-kind half of the contract: a missing mouth is what blemish and shaving shadow need
     * to exclude, and it says nothing about the skin under the eyes.
     */
    @Test
    fun `missing lip contours leave dark circles supported`() {
        val noLips = frontal().withoutRegions(FaceRegion.UpperLip, FaceRegion.LowerLip)

        val support = supportOf(squareFace(MIN_FACE_PX), noLips)

        assertEquals(KindSupport.Supported, support.getValue(SkinRetouchKind.DarkCircles))
        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.MissingRegions),
            support.getValue(SkinRetouchKind.Blemish),
        )
        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.MissingRegions),
            support.getValue(SkinRetouchKind.ShavingShadow),
        )
    }

    @Test
    fun `missing eye contours leave dark circles unsupported`() {
        val noEyes = frontal().withoutRegions(FaceRegion.LeftEye)

        val support = supportOf(squareFace(MIN_FACE_PX), noEyes)

        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.MissingRegions),
            support.getValue(SkinRetouchKind.DarkCircles),
        )
    }

    /** An empty contour is a missing one: no landmark may be replaced by a zero coordinate. */
    @Test
    fun `an empty contour list counts as missing`() {
        val emptyOval = frontal().let { it.copy(regions = it.regions + (FaceRegion.FaceOval to emptyList())) }

        assertEquals(
            KindSupport.Unsupported(UnsupportedReason.MissingRegions),
            supportOf(squareFace(MIN_FACE_PX), emptyOval).getValue(SkinRetouchKind.Shine),
        )
    }

    @Test
    fun `a face re-detected where it was asked for is the same face`() {
        val requested = Rect(500, 400, 900, 800)
        val redetected = Rect(508, 396, 904, 806)

        assertTrue(isSameFace(requested, redetected))
    }

    @Test
    fun `a different face inside the same roi is not the requested one`() {
        val requested = Rect(500, 400, 900, 800)
        val other = Rect(820, 700, 1000, 880)

        assertFalse(isSameFace(requested, other))
    }

    @Test
    fun `a roi with no face at all is not the requested one`() {
        assertFalse(isSameFace(Rect(500, 400, 900, 800), Rect(0, 0, 0, 0)))
    }

    /** specs/skin_retouch_pipeline.md §4: ROI-local points come back as canonical ones. */
    @Test
    fun `roi local contour points map back to canonical coordinates`() {
        val roi = Rect(420, 320, 980, 880)

        val canonical = toCanonical(PointF(10f, 20f), roi)

        assertEquals(430f, canonical.x, 0f)
        assertEquals(340f, canonical.y, 0f)
    }

    private fun face(id: String, bounds: Rect) =
        DetectedFace(id = id, bounds = bounds, roi = roiFor(bounds, CANVAS, CANVAS))

    private fun squareFace(size: Int) = Rect(0, 0, size, size)

    /** Every contour ML Kit gives for a frontal face, as a single point each — presence is what
     * `supportOf` reads, and a one-point contour keeps the fixture legible. */
    private fun frontal() = FaceGeometry(
        yawDegrees = 0f,
        rollDegrees = 0f,
        regions = FaceRegion.entries.associateWith { listOf(PointF(1f, 1f)) },
    )

    private fun FaceGeometry.withoutRegions(vararg missing: FaceRegion) =
        copy(regions = regions - missing.toSet())

    private companion object {
        const val CANVAS = 2000
    }
}
