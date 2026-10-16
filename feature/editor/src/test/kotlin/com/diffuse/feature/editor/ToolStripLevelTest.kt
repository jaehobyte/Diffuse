package com.diffuse.feature.editor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * work/decisions.md D107: one strip, one level — the manual tools and the AI circle, which is
 * 지시. The plan-only tools have no item anywhere.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The strip is a `LazyRow`, so an item past the viewport is not composed at all. Six 64dp items
// need a real phone's width to be reachable without scrolling.
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ToolStripLevelTest {

    @get:Rule
    val compose = createComposeRule()

    private val clicked = mutableListOf<Tool>()
    private var aiClicks = 0

    // ---- the list, without a screenshot ----------------------------------

    /** work/decisions.md D092: one menu for every photograph — every root tool, once, in order. */
    @Test
    fun `the strip holds the manual tools in order and ends at AI`() {
        val items = stripItems()

        assertEquals(
            listOf(Tool.Light, Tool.Color, Tool.Mix, Tool.Crop, Tool.Detail),
            items.filterIsInstance<StripItem.OfTool>().map { it.tool },
        )
        assertEquals(StripItem.OpenAi, items.last())
    }

    /** D107: a tool in the `Ai` group is reached only through a plan — it has no item. */
    @Test
    fun `a plan-only tool has no strip item`() {
        val shown = stripItems().filterIsInstance<StripItem.OfTool>().map { it.tool }.toSet()

        assertEquals(Tool.entries.filter { it.group == ToolGroup.Root }.toSet(), shown)
        assertTrue(Tool.entries.filter { it.group == ToolGroup.Ai }.none { it in shown })
        assertTrue(Tool.Direct.group == ToolGroup.Ai)
    }

    // ---- the strip -------------------------------------------------------

    @Test
    fun `the strip shows the adjust tools and AI, and none of the plan-only tools`() {
        show()

        compose.onNodeWithText("라이트").assertExists()
        compose.onNodeWithText("자르기").assertExists()
        compose.onNodeWithText("디테일").assertExists()
        compose.onNodeWithText("AI").assertExists()
        listOf("뒤로", "지우기", "채우기", "확대", "화질 개선", "스타일", "멀티샷", "지시", "자동", "선택").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    /** D107: tapping AI is 지시 — reported to the caller as the AI click, selecting no tool. */
    @Test
    fun `tapping AI reports the AI click and selects no tool`() {
        show()

        compose.onNodeWithText("AI").performClick()

        assertEquals(1, aiClicks)
        assertEquals(emptyList<Tool>(), clicked)
    }

    @Test
    fun `a tool tap reaches the tool`() {
        show()

        compose.onNodeWithText("자르기").performClick()

        assertEquals(listOf(Tool.Crop), clicked)
        assertEquals(0, aiClicks)
    }

    // ---- disabling -------------------------------------------------------

    /** The AI circle is never greyed: 지시 explains a missing key itself (specs/vibe_edit.md §10). */
    @Test
    fun `AI still opens when every tool is disabled`() {
        show(disabled = Tool.entries.toSet())

        compose.onNodeWithText("AI").assertIsEnabled()
        compose.onNodeWithText("AI").performClick()

        assertEquals(1, aiClicks)
    }

    /** DESIGN.md §4 greys through colour, not through the click, so a tool can explain itself. */
    @Test
    fun `nothing in the strip is ever click-disabled`() {
        show(disabled = Tool.entries.toSet())

        stripItems().filterIsInstance<StripItem.OfTool>().forEach {
            compose.onNodeWithText(labelOf(it.tool)).assertIsEnabled()
            compose.onNodeWithText(labelOf(it.tool)).performClick()
        }

        assertEquals(stripItems().filterIsInstance<StripItem.OfTool>().map { it.tool }, clicked)
    }

    private fun labelOf(tool: Tool): String = when (tool) {
        Tool.Light -> "라이트"
        Tool.Color -> "색상"
        Tool.Mix -> "혼합"
        Tool.Crop -> "자르기"
        Tool.Detail -> "디테일"
        Tool.Erase, Tool.Fill, Tool.Expand, Tool.Enhance, Tool.Style, Tool.MultiShot, Tool.Direct ->
            error("$tool has no strip item")
    }

    private fun show(disabled: Set<Tool> = emptySet()) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                EditorToolStrip(
                    selectedTool = null,
                    onToolClick = { clicked += it },
                    disabledTools = disabled,
                    onAiClick = { aiClicks++ },
                )
            }
        }
        compose.waitForIdle()
    }
}
