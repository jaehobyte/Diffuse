package com.diffuse.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * EditorRoute always passes a sheet lambda. An unselected tool is an empty slot;
 * only a measured sheet should hide the speak control that opens the direct planner.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class VibeChromeVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty sheet slot leaves the speech overlay up`() {
        show(sheet = {})

        compose.onNodeWithTag(VibePromptTestTag).assertExists()
        compose.onNodeWithTag(VibeSpeakTestTag).assertExists()
    }

    @Test
    fun `a measured sheet hides the speech overlay`() {
        show(
            sheet = {
                Box(
                    modifier = Modifier
                        .testTag("MeasuredSheet")
                        .fillMaxWidth()
                        .height(300.dp),
                )
            },
        )

        compose.onNodeWithTag("MeasuredSheet").assertExists()
        compose.onNodeWithTag(VibePromptTestTag).assertDoesNotExist()
    }

    private fun show(sheet: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = null,
                onToolClick = {},
                canUndo = false,
                canRedo = false,
                canCompare = false,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                initialToolsRevealed = false,
                sheet = sheet,
            )
        }
        compose.waitForIdle()
    }
}
