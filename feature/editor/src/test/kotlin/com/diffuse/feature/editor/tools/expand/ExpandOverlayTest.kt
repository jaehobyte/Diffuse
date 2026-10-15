package com.diffuse.feature.editor.tools.expand

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas
import com.diffuse.feature.editor.canvas.LocalCanvasTransform
import com.diffuse.feature.editor.canvas.OverlayTransform
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/outpaint.md §6's drag on the real canvas: every margin change refits the frame under
 * the finger, and the drag has to survive that — a gesture restarted on each refit kept only
 * its first move, which is what made 확대 look like it did not expand at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ExpandOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a drag in several moves keeps growing the margin while the canvas refits under it`() {
        var margins by mutableStateOf(Margins.None)
        var frame = Rect.Zero
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                var viewport by remember { mutableStateOf(CanvasViewport()) }
                EditorCanvas(
                    bitmap = testImage(),
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    overlayTransform = OverlayTransform(margins = margins, roomForHandles = true),
                    overlay = {
                        frame = LocalCanvasTransform.current.imageRect
                        ExpandOverlay(margins = margins, onMarginsChange = { margins = it })
                    },
                )
            }
        }
        compose.waitForIdle()
        val interiorHeight = frame.height
        assertTrue("the canvas laid the photo out", interiorHeight > 0f)
        val topHandle = Offset(frame.center.x, frame.top)

        compose.onNodeWithTag(ExpandOverlayTestTag).performTouchInput {
            down(topHandle)
            repeat(STEPS) { moveBy(Offset(0f, -STEP_PX)) }
            up()
        }
        compose.waitForIdle()

        // One move is worth STEP_PX of the interior's height; slop eats most of the first, and
        // the moves land within one frame, so nothing between them is recomposed.
        val oneMove = STEP_PX / interiorHeight
        assertTrue("top margin ${margins.top} kept only one move", margins.top > oneMove * (STEPS - 3))
        assertEquals(0f, margins.bottom)
        assertEquals(0f, margins.left)
        assertEquals(0f, margins.right)
    }

    @Test
    fun `a drag that starts away from every handle changes nothing`() {
        var margins by mutableStateOf(Margins.None)
        var frame = Rect.Zero
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                var viewport by remember { mutableStateOf(CanvasViewport()) }
                EditorCanvas(
                    bitmap = testImage(),
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    overlayTransform = OverlayTransform(margins = margins, roomForHandles = true),
                    overlay = {
                        frame = LocalCanvasTransform.current.imageRect
                        ExpandOverlay(margins = margins, onMarginsChange = { margins = it })
                    },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ExpandOverlayTestTag).performTouchInput {
            down(frame.center)
            repeat(STEPS) { moveBy(Offset(0f, -STEP_PX)) }
            up()
        }
        compose.waitForIdle()

        assertEquals(Margins.None, margins)
    }

    private companion object {
        const val STEPS = 6
        const val STEP_PX = 24f
    }
}
