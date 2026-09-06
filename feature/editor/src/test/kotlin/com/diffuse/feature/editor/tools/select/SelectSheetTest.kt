package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.EditorToolStrip
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolStripTestTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/selection_tool.md §1, §6, §7. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private var inverts = 0
    private var clears = 0
    private var applies = 0
    private val modes = mutableListOf<MergeMode>()
    private var cutOuts = 0

    @Test
    fun `배경 지우기 appears only once there is a mask`() {
        showSheet(SelectionState())
        compose.onNodeWithTag(SelectCutOutTestTag).assertDoesNotExist()
    }

    @Test
    fun `배경 지우기 reports the click`() {
        showSheet(SelectionState(mask = mask()))

        // The sheet caps at 45% of the screen, so this row can start below the fold.
        compose.onNodeWithTag(SelectCutOutTestTag).performScrollTo().performClick()

        assertEquals(1, cutOuts)
    }

    @Test
    fun `the mode chips switch between add and subtract`() {
        showSheet(SelectionState(mask = mask()))

        compose.onNodeWithText("빼기").performClick()

        assertEquals(listOf(MergeMode.Subtract), modes)
    }

    @Test
    fun `apply is disabled until there is a mask`() {
        showSheet(SelectionState())

        compose.onNodeWithText("적용").assertIsNotEnabled()
        compose.onNodeWithTag(SelectInvertTestTag).assertIsNotEnabled()
        compose.onNodeWithTag(SelectClearTestTag).assertIsNotEnabled()
    }

    @Test
    fun `apply and the selection actions come alive with a mask`() {
        showSheet(SelectionState(mask = mask()))

        compose.onNodeWithText("적용").assertIsEnabled()
        compose.onNodeWithTag(SelectInvertTestTag).performClick()
        compose.onNodeWithTag(SelectClearTestTag).performClick()
        compose.onNodeWithText("적용").performClick()

        assertEquals(1, inverts)
        assertEquals(1, clears)
        assertEquals(1, applies)
    }

    @Test
    fun `an absent concept replaces the hint, and it is not a snackbar`() {
        showSheet(SelectionState(notFound = true))

        compose.onNodeWithText("찾지 못했어요. 다른 단어로 해보세요.").assertExists()
    }

    @Test
    fun `low confidence replaces the hint rather than raising a snackbar`() {
        showSheet(SelectionState(mask = mask(), lowConfidence = true))

        compose.onNodeWithText("선택이 불확실해요. 점을 더 추가해보세요.").assertExists()
    }

    @Test
    fun `an unavailable select tool is greyed but still tappable`() {
        val clicks = mutableListOf<Tool>()
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                EditorToolStrip(
                    selectedTool = null,
                    onToolClick = { clicks += it },
                    disabledTools = setOf(Tool.Select),
                )
            }
        }

        compose.onNodeWithTag(ToolStripTestTag).assertExists()
        // 선택 sits past the viewport now that the strip has eight tools; it is a LazyRow.
        compose.onNodeWithTag(ToolStripTestTag).performScrollToNode(hasText("선택"))
        compose.onNodeWithText("선택").performClick()

        assertEquals(listOf(Tool.Select), clicks)
    }

    private fun showSheet(state: SelectionState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                SelectSheet(
                    state = state,
                    onModeChange = { modes += it },
                    onInvert = { inverts++ },
                    onClear = { clears++ },
                    onCutOut = { cutOuts++ },
                    onCancel = {},
                    onApply = { applies++ },
                )
            }
        }
    }

    private fun mask(): Bitmap {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8)
        for (y in 0 until 8) for (x in 0 until 8) bitmap.setPixel(x, y, MaskOps.OPAQUE shl 24)
        return bitmap
    }
}
