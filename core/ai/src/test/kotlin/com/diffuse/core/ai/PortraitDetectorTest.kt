package com.diffuse.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * work/decisions.md T79. ML Kit itself needs Play services, which no JVM test has, so what is
 * tested here is the half that decides — the threshold — and not the half that detects.
 */
class PortraitDetectorTest {

    @Test
    fun `a face filling a tenth of the width is a portrait`() {
        assertEquals(PortraitResult.Portrait, portraitResultOf(listOf(FACE_AT_TENTH), WIDTH))
    }

    /**
     * Either bar, so on a preview-sized frame the lower of the two is what governs: 100px, not
     * the 108 a tenth of 1080 would ask for.
     */
    @Test
    fun `a face just under both bars is not`() {
        assertEquals(
            PortraitResult.NotPortrait,
            portraitResultOf(listOf(MIN_FACE_WIDTH_PX - 1), WIDTH),
        )
        assertEquals(PortraitResult.Portrait, portraitResultOf(listOf(MIN_FACE_WIDTH_PX), WIDTH))
    }

    /** The pixel bar alone: on a narrow frame a 100px face is well past a tenth of the width. */
    @Test
    fun `a hundred pixel face clears the bar on a narrow frame`() {
        assertEquals(
            PortraitResult.Portrait,
            portraitResultOf(listOf(MIN_FACE_WIDTH_PX), NARROW_WIDTH),
        )
    }

    /** The ratio bar alone: on a wide frame a tenth of the width is past 100px. */
    @Test
    fun `a tenth of a wide frame clears the bar below a hundred pixels is not needed`() {
        assertEquals(PortraitResult.Portrait, portraitResultOf(listOf(WIDE_TENTH), WIDE_WIDTH))
    }

    /** T79: a crowd in the background is not a retouch subject. */
    @Test
    fun `many small faces are still not a portrait`() {
        assertEquals(PortraitResult.NotPortrait, portraitResultOf(List(CROWD) { SMALL_FACE }, WIDTH))
    }

    /** One big face among small ones is enough — the biggest is the one being retouched. */
    @Test
    fun `one large face among small ones is a portrait`() {
        val faces = List(CROWD) { SMALL_FACE } + FACE_AT_TENTH
        assertEquals(PortraitResult.Portrait, portraitResultOf(faces, WIDTH))
    }

    @Test
    fun `no faces is not a portrait, and never Unknown`() {
        assertEquals(PortraitResult.NotPortrait, portraitResultOf(emptyList(), WIDTH))
    }

    private companion object {
        /** `EditorViewModel.PREVIEW_LONG_EDGE_PX` on a square frame: what the detector is fed. */
        const val WIDTH = 1080
        const val FACE_AT_TENTH = 108
        const val SMALL_FACE = 20

        const val NARROW_WIDTH = 400
        const val WIDE_WIDTH = 2000
        const val WIDE_TENTH = 200

        const val CROWD = 5
    }
}
