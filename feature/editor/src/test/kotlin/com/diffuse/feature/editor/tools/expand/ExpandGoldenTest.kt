package com.diffuse.feature.editor.tools.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas
import com.diffuse.feature.editor.canvas.OverlayTransform
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/outpaint.md §8's two UI goldens. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ExpandGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * §6: the photo shrinks to leave room, the pending area is the checkerboard DESIGN.md §2
     * already defines for "no pixels here", and the four handles sit on the edge midpoints.
     */
    @Test
    fun expandOverlay() {
        val image = testImage()
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                var viewport by remember { mutableStateOf(CanvasViewport()) }
                EditorCanvas(
                    bitmap = image,
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    overlayTransform = OverlayTransform(margins = MARGINS),
                    overlay = { ExpandOverlay(margins = MARGINS, onMarginsChange = {}) },
                )
            }
        }
        compose.waitForIdle()

        capture("expand_overlay")
    }

    @Test
    fun expandSheetOpen() {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    ExpandSheet(
                        state = ExpandState(margins = MARGINS),
                        sourceAspect = SOURCE_ASPECT,
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()

        capture("expand_sheet_open")
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    private companion object {
        const val SOURCE_ASPECT = 4f / 3f

        /** §1's actual job: a landscape photo on its way to a portrait ratio, here 4:3 → 4:5. */
        val MARGINS = Margins(left = 0.1f, top = 0.5f, right = 0.1f, bottom = 0.5f)
    }
}
