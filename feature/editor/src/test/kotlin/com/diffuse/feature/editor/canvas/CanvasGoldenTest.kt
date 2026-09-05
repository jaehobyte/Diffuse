package com.diffuse.feature.editor.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class CanvasGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showCanvas(image: ImageBitmap) {
        compose.setContent {
            var viewport by remember { mutableStateOf(CanvasViewport()) }
            EditorCanvas(
                bitmap = image,
                viewport = viewport,
                onViewportChange = { viewport = it },
            )
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    @Test
    fun canvasFit() {
        showCanvas(testImage())

        capture("canvas_fit")
    }

    @Test
    fun canvasZoomed() {
        showCanvas(testImage())

        compose.onNodeWithTag(EditorCanvasTestTag).performTouchInput { doubleClick() }
        compose.waitForIdle()

        capture("canvas_zoomed")
    }

    @Test
    fun canvasTransparent() {
        showCanvas(testImage(transparentQuadrant = true))

        capture("canvas_transparent")
    }
}
