package com.diffuse.feature.editor.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorScreen
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.canvas.testImage
import com.diffuse.feature.editor.tools.color.ColorKinds
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** T14 done-when: the Color sheet opens from the tool strip. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ToolSheetHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)

    private fun showEditor() {
        compose.setContent {
            var selected by remember { mutableStateOf<Tool?>(null) }
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
                // Tapping the selected tool again closes it (specs/editor_shell.md).
                onToolClick = { selected = if (selected == it) null else it },
                canUndo = false,
                canRedo = false,
                canCompare = false,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                sheet = {
                    ToolSheetHost(
                        selectedTool = selected,
                        document = document,
                        onValueChange = { _, _ -> },
                        onValueChangeFinished = {},
                        onCancel = { selected = null },
                        onApply = { selected = null },
                    )
                },
            )
        }
        compose.waitForIdle()
    }

    private fun toolTag(tool: Tool): String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(tool.labelRes)

    @Test
    fun `tapping Color opens the color sheet from the tool strip`() {
        showEditor()
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()

        compose.onNodeWithTag(toolTag(Tool.Color)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(EditSheetTestTag).assertExists()
        ColorKinds.forEach { compose.onNodeWithTag(adjustSliderTag(it)).assertExists() }
    }

    @Test
    fun `tapping Light opens the light sheet`() {
        showEditor()

        compose.onNodeWithTag(toolTag(Tool.Light)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(adjustSliderTag(AdjustKind.Exposure)).assertExists()
    }

    @Test
    fun `tapping the selected tool again closes its sheet`() {
        showEditor()
        compose.onNodeWithTag(toolTag(Tool.Color)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(toolTag(Tool.Color)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
    }
}
