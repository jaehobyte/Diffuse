package com.diffuse.feature.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
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
class EditorShellGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editorShellDefault() {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = Tool.Light,
                onToolClick = {},
                canUndo = true,
                canRedo = false,
                canCompare = true,
                canReset = true,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
            )
        }
        compose.waitForIdle()

        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath("editor_shell_default"),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
