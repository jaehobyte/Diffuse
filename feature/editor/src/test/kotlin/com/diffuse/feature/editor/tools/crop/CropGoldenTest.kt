package com.diffuse.feature.editor.tools.crop

import android.graphics.RectF
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
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas
import com.diffuse.feature.editor.canvas.testImage
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
class CropGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    @Test
    fun cropOverlay() {
        val image = testImage()
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                var viewport by remember { mutableStateOf(CanvasViewport()) }
                EditorCanvas(
                    bitmap = image,
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    overlay = {
                        CropOverlay(
                            rect = RectF(0.15f, 0.2f, 0.85f, 0.8f),
                            onRectChange = {},
                        )
                    },
                )
            }
        }
        compose.waitForIdle()

        capture("crop_overlay")
    }

    @Test
    fun cropSheetOpen() {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    CropSheet(
                        preset = AspectPreset.FourFive,
                        straightenDeg = -12.5f,
                        onPresetChange = {},
                        onStraightenChange = {},
                        onStraightenFinished = {},
                        onRotate = {},
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()

        capture("crop_sheet_open")
    }
}
