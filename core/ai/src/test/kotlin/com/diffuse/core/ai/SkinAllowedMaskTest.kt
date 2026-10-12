package com.diffuse.core.ai

import android.graphics.PointF
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * specs/skin_retouch_pipeline.md §3: each kind's allowance is skin in its own place, with every
 * neighbour it must keep out removed — never the face rectangle.
 */
@RunWith(RobolectricTestRunner::class)
class SkinAllowedMaskTest {

    private val roi = Rect(100, 50, 500, 550)

    @Test
    fun `blemish allows cheek skin but not eyes brows lips nostrils or outside the oval`() {
        val mask = SkinAllowedMask.pixels(SkinRetouchKind.Blemish, detail())

        assertTrue(at(mask, CHEEK))
        assertTrue(at(mask, FOREHEAD))
        assertFalse(at(mask, LEFT_EYE_CENTRE))
        assertFalse(at(mask, LEFT_BROW_CENTRE))
        assertFalse(at(mask, MOUTH))
        assertFalse(at(mask, NOSE_BOTTOM))
        assertFalse(at(mask, PointF(roi.left + 2f, roi.top + 2f)))
    }

    @Test
    fun `dark circles allow only under the eyes`() {
        val mask = SkinAllowedMask.pixels(SkinRetouchKind.DarkCircles, detail())

        assertTrue(at(mask, PointF(LEFT_EYE_CENTRE.x, LEFT_EYE_CENTRE.y + 28f)))
        assertFalse(at(mask, LEFT_EYE_CENTRE))
        assertFalse(at(mask, FOREHEAD))
        assertFalse(at(mask, MOUTH))
        assertFalse(at(mask, PointF(CHEEK.x, CHEEK.y + 90f)))
    }

    @Test
    fun `shaving shadow is below the nose and never on the lips`() {
        val mask = SkinAllowedMask.pixels(SkinRetouchKind.ShavingShadow, detail())

        assertTrue(at(mask, CHIN))
        assertTrue(at(mask, PointF(MOUTH.x, NOSE_BOTTOM.y + 22f)))
        assertFalse(at(mask, MOUTH))
        assertFalse(at(mask, CHEEK))
        assertFalse(at(mask, FOREHEAD))
    }

    @Test
    fun `another face overlapping the roi is excluded`() {
        val other = DetectedFace("other", Rect(250, 350, 520, 600), Rect(200, 300, 560, 650))
        val mask = SkinAllowedMask.pixels(SkinRetouchKind.Blemish, detail(), listOf(other))

        assertFalse(at(mask, CHIN))
        assertTrue(at(mask, FOREHEAD))
    }

    @Test
    fun `no oval means nothing is allowed rather than the rectangle`() {
        val detail = detail().let {
            it.copy(geometry = it.geometry.copy(regions = it.geometry.regions - FaceRegion.FaceOval))
        }

        assertTrue(SkinAllowedMask.pixels(SkinRetouchKind.Blemish, detail).none { it })
    }

    @Test
    fun `the bitmap is binary alpha at the roi size`() {
        val bitmap = SkinAllowedMask.bitmap(SkinRetouchKind.Blemish, detail())
        val flags = SkinAllowedMask.pixels(SkinRetouchKind.Blemish, detail())

        assertEquals(roi.width(), bitmap.width)
        assertEquals(roi.height(), bitmap.height)
        for (y in 0 until roi.height() step 7) {
            for (x in 0 until roi.width() step 7) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                assertEquals(if (flags[y * roi.width() + x]) 255 else 0, alpha)
            }
        }
    }

    @Test
    fun `the polygon fill covers pixel centres inside and nothing outside`() {
        val square = listOf(PointF(1f, 1f), PointF(4f, 1f), PointF(4f, 3f), PointF(1f, 3f))
        val into = BooleanArray(36)

        SkinAllowedMask.fill(square, into, 6, 6, true)

        val filled = into.indices.filter { into[it] }.map { it % 6 to it / 6 }.toSet()
        assertEquals(setOf(1 to 1, 2 to 1, 3 to 1, 1 to 2, 2 to 2, 3 to 2), filled)
    }

    private fun at(mask: BooleanArray, point: PointF): Boolean {
        val x = (point.x - roi.left).toInt()
        val y = (point.y - roi.top).toInt()
        return mask[y * roi.width() + x]
    }

    /** A frontal synthetic face in canonical coordinates, shaped like ML Kit's contours. */
    private fun detail(): FaceDetail {
        val face = DetectedFace("face", Rect(160, 120, 440, 480), roi)
        val regions = mapOf(
            FaceRegion.FaceOval to ellipse(300f, 300f, 130f, 175f, 36),
            FaceRegion.LeftEye to ellipse(LEFT_EYE_CENTRE.x, LEFT_EYE_CENTRE.y, 26f, 9f, 16),
            FaceRegion.RightEye to ellipse(360f, 250f, 26f, 9f, 16),
            FaceRegion.LeftEyebrow to ellipse(LEFT_BROW_CENTRE.x, LEFT_BROW_CENTRE.y, 32f, 6f, 10),
            FaceRegion.RightEyebrow to ellipse(360f, 222f, 32f, 6f, 10),
            FaceRegion.UpperLip to ellipse(MOUTH.x, MOUTH.y - 6f, 40f, 7f, 20),
            FaceRegion.LowerLip to ellipse(MOUTH.x, MOUTH.y + 6f, 38f, 8f, 18),
            FaceRegion.NoseBridge to listOf(PointF(300f, 250f), PointF(300f, 320f)),
            FaceRegion.NoseBottom to listOf(PointF(282f, NOSE_BOTTOM.y), NOSE_BOTTOM, PointF(318f, NOSE_BOTTOM.y)),
        )
        val geometry = FaceGeometry(0f, 0f, regions)
        return FaceDetail(face, geometry, supportOf(face.bounds, geometry))
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int) = (0 until n).map {
        val t = 2 * Math.PI * it / n
        PointF(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
    }

    private companion object {
        val LEFT_EYE_CENTRE = PointF(240f, 250f)
        val LEFT_BROW_CENTRE = PointF(240f, 222f)
        val NOSE_BOTTOM = PointF(300f, 335f)
        val MOUTH = PointF(300f, 395f)
        val CHEEK = PointF(220f, 320f)
        val FOREHEAD = PointF(300f, 175f)
        val CHIN = PointF(300f, 445f)
    }
}
