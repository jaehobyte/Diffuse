package com.diffuse.feature.editor.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorScreen
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T57. The report: "디테일 탭 눌렀다가 취소하면 그 다음에 빛 탭이 뜨는데 이건 누른적이 없는데".
 *
 * These are the reproduction attempts, and they all pass — the leak does not happen here. They are
 * kept as a guard rather than deleted: 취소 really does sit directly over the strip's leftmost item
 * (measured, see blocked.md), so anything that later changes how a sheet is dismissed — an exit
 * animation, a scrim, a different sheet host — would turn that overlap into the reported bug, and
 * these three would catch it. See blocked.md for what was tried and what a human still needs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class SheetCancelTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
    private val toolClicks = mutableListOf<Tool>()

    private fun showEditor() {
        toolClicks.clear()
        compose.setContent {
            var selected by remember { mutableStateOf<Tool?>(null) }
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
                onToolClick = {
                    toolClicks += it
                    selected = if (selected == it) null else it
                },
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

    private fun openDetail() {
        compose.onNodeWithTag(toolTag(Tool.Detail)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EditSheetTestTag).assertExists()
        toolClicks.clear()
    }

    @Test
    fun `tapping 취소 closes the sheet and selects no other tool`() {
        showEditor()
        openDetail()

        compose.onNodeWithText("취소").performClick()
        compose.waitForIdle()

        assertEquals("취소 must not reach the tool strip", emptyList<Tool>(), toolClicks)
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
    }

    /** The decisive one: can a touch reach the strip *through* an open sheet at all? */
    @Test
    fun `the tool strip is not reachable beneath an open sheet`() {
        showEditor()
        openDetail()

        val light = compose.onNodeWithTag(toolTag(Tool.Light)).getUnclippedBoundsInRoot()
        val point = with(compose.density) {
            Offset(
                ((light.left + light.right) / 2).toPx(),
                ((light.top + light.bottom) / 2).toPx(),
            )
        }

        compose.onRoot().performTouchInput { down(point); up() }
        compose.waitForIdle()

        assertEquals("nothing beneath the sheet may be tapped", emptyList<Tool>(), toolClicks)
    }

    /**
     * The device's gesture, modelled: the press lands on 취소, the sheet is removed while the
     * finger is still down, and the release then falls on whatever is now under it.
     */
    @Test
    fun `a press on 취소 released after the sheet closes reaches no tool`() {
        showEditor()
        openDetail()

        val cancel = compose.onNodeWithText("취소").getUnclippedBoundsInRoot()
        val density = compose.density
        val point = with(density) {
            Offset(
                ((cancel.left + cancel.right) / 2).toPx(),
                ((cancel.top + cancel.bottom) / 2).toPx(),
            )
        }

        compose.onRoot().performTouchInput { down(point) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("the release must not reach the tool strip", emptyList<Tool>(), toolClicks)
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()
    }
}
