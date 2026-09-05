package com.diffuse.feature.editor.canvas

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** specs/canvas.md gestures, asserted on viewport values rather than pixels. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class CanvasGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var viewport: MutableState<CanvasViewport>

    private fun showCanvas() {
        val image = testImage()
        compose.setContent {
            val state = remember { mutableStateOf(CanvasViewport()) }
            viewport = state
            EditorCanvas(
                bitmap = image,
                viewport = state.value,
                onViewportChange = { state.value = it },
            )
        }
        compose.waitForIdle()
    }

    private fun canvas() = compose.onNodeWithTag(EditorCanvasTestTag)

    @Test
    fun `the canvas starts fitted`() {
        showCanvas()

        assertTrue("expected a positive fit scale", viewport.value.fitScale > 0f)
        assertTrue("expected to start fitted", viewport.value.isFitted)
    }

    @Test
    fun `pinching far out clamps to half the fit scale`() {
        showCanvas()
        val fit = viewport.value.fitScale

        // Touch slop eats the opening of each gesture, so pinch twice to reach the clamp.
        repeat(2) {
            canvas().performTouchInput {
                pinch(
                    start0 = center + Offset(-450f, 0f),
                    end0 = center + Offset(-30f, 0f),
                    start1 = center + Offset(450f, 0f),
                    end1 = center + Offset(30f, 0f),
                )
            }
            compose.waitForIdle()
        }

        assertEquals(fit * CanvasMath.MIN_SCALE_FACTOR, viewport.value.scale, 0.01f)
    }

    @Test
    fun `pinching far in clamps to eight times the fit scale`() {
        showCanvas()
        val fit = viewport.value.fitScale

        repeat(2) {
            canvas().performTouchInput {
                pinch(
                    start0 = center + Offset(-30f, 0f),
                    end0 = center + Offset(-450f, 0f),
                    start1 = center + Offset(30f, 0f),
                    end1 = center + Offset(450f, 0f),
                )
            }
            compose.waitForIdle()
        }

        assertEquals(fit * CanvasMath.MAX_SCALE_FACTOR, viewport.value.scale, 0.01f)
    }

    @Test
    fun `double tap toggles between fit and 2x`() {
        showCanvas()
        val fit = viewport.value.fitScale

        canvas().performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(fit * CanvasMath.DOUBLE_TAP_FACTOR, viewport.value.scale, 0.01f)

        canvas().performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(fit, viewport.value.scale, 0.01f)
        assertEquals(Offset.Zero, viewport.value.offset)
    }

    @Test
    fun `panning is clamped so part of the image stays on screen`() {
        showCanvas()
        val size = canvas().fetchSemanticsNode().size
        val image = testImage()

        canvas().performTouchInput {
            swipe(start = center, end = center + Offset(4000f, 0f), durationMillis = 200)
        }
        compose.waitForIdle()

        val scaled = image.width * viewport.value.scale
        val maxX = size.width / 2f + (0.5f - CanvasMath.MIN_VISIBLE_FRACTION) * scaled
        assertTrue(
            "offset ${viewport.value.offset.x} exceeded the clamp $maxX",
            abs(viewport.value.offset.x) <= maxX + 1f,
        )
        assertTrue("expected the pan to actually move", viewport.value.offset.x > 0f)
    }
}
