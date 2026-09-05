package com.diffuse.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.components.PrimaryPill
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import androidx.compose.ui.unit.dp
import com.diffuse.feature.editor.canvas.EditorCanvasTestTag
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

    private val naturalPillTag = "NaturalPill"
    private val fakeSheetTag = "FakeSheet"
    private val fakeSheetHeight = 300.dp

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

    /**
     * The Korean export label is wide. On a 360dp phone the bar used to hand the pill
     * whatever the icons left over, which clipped "내보내기" down to an icon's width.
     */
    @Test
    @Config(qualifiers = "+w360dp-h780dp")
    fun `the export pill keeps its label width on a narrow phone`() {
        compose.setContent {
            AppTheme(mode = ThemeMode.Edit) {
                Column {
                    EditorTopBar(
                        canUndo = true, canRedo = true, canReset = true, canCompare = true,
                        onBack = {}, onUndo = {}, onRedo = {}, onReset = {},
                        onCompareChange = {}, onExport = {},
                    )
                    // The same pill with nothing competing for the row: its natural width.
                    PrimaryPill(
                        text = labelOf(R.string.editor_export),
                        onClick = {},
                        modifier = Modifier.testTag(naturalPillTag),
                    )
                }
            }
        }
        compose.waitForIdle()

        val bar = compose.onNodeWithTag(TopBarTestTag).getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag(ExportTestTag).getUnclippedBoundsInRoot()
        val natural = compose.onNodeWithTag(naturalPillTag).getUnclippedBoundsInRoot()

        assertTrue(
            "the export label is squeezed: pill is ${pill.right - pill.left}, " +
                "the label needs ${natural.right - natural.left}",
            pill.right - pill.left >= natural.right - natural.left,
        )
        assertTrue(
            "the export pill runs past the bar: pill ends at ${pill.right}, bar at ${bar.right}",
            pill.right <= bar.right,
        )
    }

    /**
     * `EditorRoute` always passes a sheet lambda and empties it when the tool closes, so
     * the shell is driven the same way here.
     */
    private fun showShellWithSheet(open: () -> Boolean) {
        compose.setContent {
            EditorScreen(
                preview = testImage(),
                selectedTool = Tool.Light,
                onToolClick = {},
                canUndo = false, canRedo = false, canCompare = false,
                onBack = {}, onUndo = {}, onRedo = {}, onCompareChange = {}, onExport = {},
                sheet = {
                    if (open()) {
                        Box(
                            modifier = Modifier
                                .testTag(fakeSheetTag)
                                .fillMaxWidth()
                                .height(fakeSheetHeight),
                        )
                    }
                },
            )
        }
        compose.waitForIdle()
    }

    private fun canvasBounds() =
        compose.onNodeWithTag(EditorCanvasTestTag).getUnclippedBoundsInRoot()

    /**
     * An open sheet eats the bottom of the screen, so the canvas has to give that space
     * up: otherwise the photo sits behind the sheet and the user has to pinch it out.
     */
    @Test
    fun `an open sheet shrinks the canvas instead of covering it`() {
        showShellWithSheet { true }

        val canvas = canvasBounds()
        val sheet = compose.onNodeWithTag(fakeSheetTag).getUnclippedBoundsInRoot()

        assertTrue(
            "the canvas runs under the sheet: canvas ends at ${canvas.bottom}, " +
                "sheet starts at ${sheet.top}",
            canvas.bottom <= sheet.top,
        )
    }

    @Test
    fun `closing the sheet gives the canvas its space back`() {
        var open by mutableStateOf(true)
        showShellWithSheet { open }
        val shrunk = canvasBounds()

        open = false
        compose.waitForIdle()

        assertTrue(
            "the canvas kept the closed sheet's inset: ${canvasBounds().bottom} vs ${shrunk.bottom}",
            canvasBounds().bottom > shrunk.bottom,
        )
    }

    private fun labelOf(tool: Tool): String = labelOf(tool.labelRes)

    private fun labelOf(resId: Int): String =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(resId)
}
