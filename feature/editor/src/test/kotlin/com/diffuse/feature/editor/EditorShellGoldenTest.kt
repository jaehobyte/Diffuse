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
    fun editorShellDefault() = shell("editor_shell_default", Tool.Light)

    /** D107: the AI circle is 지시 itself, and its label goes yellow while 지시 is open or listening. */
    @Test
    fun editorShellAiActive() = shell("editor_shell_ai_active", null, aiActive = true)

    private fun shell(name: String, selected: Tool?, aiActive: Boolean = false) {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
                onToolClick = {},
                canUndo = true,
                canRedo = false,
                canCompare = true,
                canReset = true,
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                aiActive = aiActive,
            )
        }
        compose.waitForIdle()

        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
