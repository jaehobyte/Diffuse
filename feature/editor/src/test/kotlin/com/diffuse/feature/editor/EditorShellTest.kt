package com.diffuse.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class EditorShellTest {

    @get:Rule
    val compose = createComposeRule()

    private val toolClicks = mutableListOf<Tool>()
    private val compareStates = mutableListOf<Boolean>()

    private fun showShell(
        preview: ImageBitmap? = testImage(),
        selectedTool: Tool? = null,
        canUndo: Boolean = false,
        canRedo: Boolean = false,
        canCompare: Boolean = false,
    ) {
        compose.setContent {
            EditorScreen(
                preview = preview,
                selectedTool = selectedTool,
                onToolClick = { toolClicks += it },
                canUndo = canUndo,
                canRedo = canRedo,
                canCompare = canCompare,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = { compareStates += it },
                onExport = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the shell shows the top bar, canvas and all four tools`() {
        showShell()

        compose.onNodeWithTag(TopBarTestTag).assertExists()
        compose.onNodeWithTag(ToolStripTestTag).assertExists()
        Tool.entries.forEach { tool ->
            compose.onNodeWithTag(labelOf(tool)).assertExists()
        }
    }

    @Test
    fun `tapping a tool reports it to the caller`() {
        showShell()

        compose.onNodeWithTag(labelOf(Tool.Crop)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(Tool.Crop), toolClicks)
    }

    @Test
    fun `undo and redo are disabled until the history has entries`() {
        showShell(canUndo = false, canRedo = true)

        compose.onNodeWithTag(labelOf(R.string.editor_undo)).assertIsNotEnabled()
        compose.onNodeWithTag(labelOf(R.string.editor_redo)).assertIsEnabled()
    }

    @Test
    fun `holding compare reports true then false on release`() {
        showShell(canCompare = true)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), compareStates)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), compareStates)
    }

    @Test
    fun `compare does nothing when there is nothing to compare against`() {
        showShell(canCompare = false)

        compose.onNodeWithTag(CompareTestTag).performTouchInput { down(center) }
        compose.onNodeWithTag(CompareTestTag).performTouchInput { up() }
        compose.waitForIdle()

        assertTrue("expected no compare events, got $compareStates", compareStates.isEmpty())
    }

    @Test
    fun `the export pill is present`() {
        showShell()

        compose.onNodeWithText(labelOf(R.string.editor_export)).assertExists()
    }

    private fun labelOf(tool: Tool): String = labelOf(tool.labelRes)

    private fun labelOf(resId: Int): String =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(resId)
}
