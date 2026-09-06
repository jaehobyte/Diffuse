package com.diffuse.core.imaging.style

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.render.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * specs/style_match.md §9. The load-bearing one is the first: a reference measured from a preset's
 * **own output** ranks that preset first. Everything else the matcher does rests on that, and it is
 * the property that survives a change of formula — which matters, because §5's named source
 * (`reference_style.py`) was never imported and `StyleMatch.measure` is our own algebra.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StyleMatchTest {

    private val presets = StyleCatalog.load(RuntimeEnvironment.getApplication().assets)

    @Test
    fun `a neutral photograph measures as no style at all`() {
        StyleMatch.measure(neutral()).forEach { (kind, value) ->
            assertEquals(kind.toString(), 0f, value, NEUTRAL_TOLERANCE)
        }
    }

    /**
     * §9's load-bearing test, run for **every** preset rather than a chosen one.
     *
     * The reference is built from a **different** photograph than the one `StyleMatch` measures
     * its signatures from — a darker, less saturated frame — so the test is a property of the
     * matcher and not an identity. Applied to the signature frame it would pass by construction
     * and assert nothing.
     */
    @Test
    fun `a reference made from a preset ranks that preset first`() {
        presets.forEach { preset ->
            val reference = apply(preset.params, otherPhotograph())
            val ranked = StyleMatch.rank(reference, presets)

            assertEquals(preset.id, ranked.first().preset.id)
        }
    }

    /** …and it lands near enough to be offered without asking the model (§5 step 1). */
    @Test
    fun `a reference made from a preset lands inside the threshold`() {
        presets.forEach { preset ->
            val reference = apply(preset.params, otherPhotograph())

            assertTrue(
                "${preset.id} was ${StyleMatch.rank(reference, presets).first().distance} away",
                StyleMatch.rank(reference, presets).first().isNear,
            )
        }
    }

    /** §5: past the threshold there is nothing local to offer, and the model call is earned. */
    @Test
    fun `a photograph unlike every preset is not near one`() {
        // Hard magenta: no preset in the catalog asks for anything like it.
        val odd = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(255, 0, 255)) }

        assertTrue(StyleMatch.rank(odd, presets).none { it.isNear })
    }


    /**
     * The matcher's stated limit, and its most legible case: it measures the look, and a
     * photograph's own character is part of that look. A colourless reference matches the
     * monochrome preset whatever grade was applied to it — which is right here, and is exactly
     * the confusion §5 step 2 earns its round trip on when it is wrong.
     */
    @Test
    fun `a colourless reference matches the monochrome preset`() {
        val dark = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val count = SIZE * SIZE
        for (index in 0 until count) {
            val luma = DARK_MID - DARK_HALF + 2f * DARK_HALF * index / (count - 1f)
            val grey = channel(luma)
            dark.setPixel(index % SIZE, index / SIZE, Color.rgb(grey, grey, grey))
        }

        assertEquals("bw-classic", StyleMatch.rank(dark, presets).first().preset.id)
    }

    @Test
    fun `ranking is nearest first`() {
        val distances = StyleMatch.rank(apply(emptyMap(), neutral()), presets).map { it.distance }

        assertEquals(distances.sorted(), distances)
    }

    /**
     * An ordinary photograph, and **not** the frame `StyleMatch` measures its signatures from: a
     * different mean, a different spread and a different chroma, so a match is a property of the
     * matcher rather than an identity.
     *
     * It is deliberately close to average, because the matcher measures a reference's look in
     * absolute terms and cannot tell a dark *grade* from a dark *scene* — see the test below,
     * which states that limit rather than hiding it.
     */
    private fun otherPhotograph(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val count = SIZE * SIZE
        for (index in 0 until count) {
            val luma = OTHER_MID - OTHER_HALF + 2f * OTHER_HALF * index / (count - 1f)
            val chroma = if (index % 2 == 0) OTHER_CHROMA else -OTHER_CHROMA
            bitmap.setPixel(
                index % SIZE,
                index / SIZE,
                Color.rgb(channel(luma + chroma), channel(luma), channel(luma - chroma)),
            )
        }
        return bitmap
    }

    private fun apply(params: Map<AdjustKind, Float>, bitmap: Bitmap): Bitmap =
        params.entries.fold(bitmap) { acc, (kind, value) -> Ops.adjust(kind)(acc, value) }

    /**
     * The zero of every measurement, built to hit `StyleMatch`'s neutral constants exactly: a
     * luma ramp with mean 0.5 and the neutral standard deviation, carrying a chroma that cancels
     * across the frame so red, green and blue average to the same value.
     */
    private fun neutral(): Bitmap = StyleMatch.neutralFrame()

    private fun channel(value: Float): Int = (value * FULL).toInt().coerceIn(0, FULL.toInt())

    private companion object {
        const val SIZE = 64
        const val FULL = 255f
        const val NEUTRAL_TOLERANCE = 0.06f

        // An ordinary photograph: not the signature frame, but not a dark or flat one either.
        const val OTHER_MID = 0.47f
        const val OTHER_HALF = 0.30f
        /** Half-width, so mean chroma is twice this — 0.22 against the neutral frame's 0.25. */
        const val OTHER_CHROMA = 0.11f

        const val DARK_MID = 0.3f
        const val DARK_HALF = 0.22f
    }
}
