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
    fun editorShellDefault() = shell("editor_shell_default", Tool.Light, ToolGroup.Root)

    /** specs/tool_groups.md §7: the second level, which is the whole point of T78. */
    @Test
    fun editorShellAiOpen() = shell("editor_shell_ai_open", null, ToolGroup.Ai)

    private fun shell(name: String, selected: Tool?, level: ToolGroup) {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
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
                toolLevel = ToolLevelState(level),
            )
        }
        compose.waitForIdle()

        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
