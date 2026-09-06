package com.diffuse.feature.editor

import androidx.compose.runtime.mutableStateOf
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

/** specs/tool_groups.md §2, §3, §4, §7. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The strip is a `LazyRow`, so an item past the viewport is not composed at all. Six 64dp items
// need a real phone's width to be reachable without scrolling.
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ToolStripLevelTest {

    @get:Rule
    val compose = createComposeRule()

    private val levelState = mutableStateOf(ToolGroup.Root)
    private val level: ToolGroup get() = levelState.value
    private val clicked = mutableListOf<Tool>()

    // ---- the lists, without a screenshot ---------------------------------

    @Test
    fun `every tool appears at exactly one level`() {
        val shown = ToolGroup.entries.flatMap { stripItems(it) }
            .filterIsInstance<StripItem.OfTool>()
            .map { it.tool }

        assertEquals(Tool.entries.size, shown.size)
        assertEquals(Tool.entries.toSet(), shown.toSet())
    }

    @Test
    fun `the root level ends at AI and the AI level starts at back`() {
        val root = stripItems(ToolGroup.Root)
        val ai = stripItems(ToolGroup.Ai)

        assertEquals(StripItem.OpenAi, root.last())
        assertEquals(StripItem.Back, ai.first())
        assertTrue(root.none { it is StripItem.Back })
        assertTrue(ai.none { it is StripItem.OpenAi })
    }

    @Test
    fun `the AI level holds the tools that call a provider`() {
        val ai = stripItems(ToolGroup.Ai).filterIsInstance<StripItem.OfTool>().map { it.tool }

        assertEquals(
            listOf(Tool.Select, Tool.Erase, Tool.Fill, Tool.Expand, Tool.Direct),
            ai,
        )
    }

    // ---- the strip -------------------------------------------------------

    @Test
    fun `the root level shows the adjust tools and not the AI ones`() {
        show()

        compose.onNodeWithText("라이트").assertExists()
        compose.onNodeWithText("자르기").assertExists()
        compose.onNodeWithText("AI").assertExists()
        compose.onNodeWithText("지우기").assertDoesNotExist()
        compose.onNodeWithText("뒤로").assertDoesNotExist()
    }

    /** §4: tapping AI is navigation, not a tool — it selects nothing. */
    @Test
    fun `tapping AI opens the level and selects no tool`() {
        show()

        compose.onNodeWithText("AI").performClick()

        assertEquals(ToolGroup.Ai, level)
        assertEquals(emptyList<Tool>(), clicked)
    }

    @Test
    fun `the AI level shows the AI tools and a way back`() {
        show(ToolGroup.Ai)

        compose.onNodeWithText("뒤로").assertExists()
        compose.onNodeWithText("지우기").assertExists()
        compose.onNodeWithText("확대").assertExists()
        compose.onNodeWithText("라이트").assertDoesNotExist()
        compose.onNodeWithText("AI").assertDoesNotExist()
    }

    @Test
    fun `tapping back returns to the root`() {
        show(ToolGroup.Ai)

        compose.onNodeWithText("뒤로").performClick()

        assertEquals(ToolGroup.Root, level)
        assertEquals(emptyList<Tool>(), clicked)
    }

    @Test
    fun `a child tap reaches the tool, not the level`() {
        show(ToolGroup.Ai)

        compose.onNodeWithText("지우기").performClick()

        assertEquals(listOf(Tool.Erase), clicked)
        assertEquals(ToolGroup.Ai, level)
    }

    // ---- disabling -------------------------------------------------------

    /** §4: a parent that cannot be tapped hides the reason its children cannot be. */
    @Test
    fun `the AI parent still opens when every child is disabled`() {
        show(disabled = Tool.entries.toSet())

        compose.onNodeWithText("AI").performClick()

        assertEquals(ToolGroup.Ai, level)
    }

    @Test
    fun `a disabled child is greyed but still tappable, so it can explain itself`() {
        show(ToolGroup.Ai, disabled = setOf(Tool.Erase))

        // DESIGN.md §4 greys it; specs/selection_tool.md §1 keeps the click.
        compose.onNodeWithText("지우기").performClick()

        assertEquals(listOf(Tool.Erase), clicked)
        assertEquals(ToolGroup.Ai, level)
    }

    @Test
    fun `back still works when every child is disabled`() {
        show(ToolGroup.Ai, disabled = Tool.entries.toSet())

        compose.onNodeWithText("뒤로").performClick()

        assertEquals(ToolGroup.Root, level)
    }

    @Test
    fun `an enabled tool is enabled`() {
        show(ToolGroup.Ai, disabled = setOf(Tool.Erase))

        compose.onNodeWithText("확대").assertIsEnabled()
        compose.onNodeWithText("지우기").assertIsEnabled()
    }

    /** The strip greys through colour, not through the click, so this is the alpha's own test. */
    @Test
    fun `nothing in the strip is ever click-disabled`() {
        show(ToolGroup.Root, disabled = Tool.entries.toSet())

        Tool.entries.filter { it.group == ToolGroup.Root }.forEach {
            compose.onNodeWithText(labelOf(it)).assertIsEnabled()
        }
    }

    private fun labelOf(tool: Tool): String = when (tool) {
        Tool.Light -> "라이트"
        Tool.Color -> "색상"
        Tool.Mix -> "혼합"
        Tool.Crop -> "자르기"
        Tool.Detail -> "디테일"
        Tool.Select -> "선택"
        Tool.Erase -> "지우기"
        Tool.Fill -> "채우기"
        Tool.Expand -> "확대"
        Tool.Direct -> "지시"
    }

    private fun show(
        start: ToolGroup = ToolGroup.Root,
        disabled: Set<Tool> = emptySet(),
    ) {
        levelState.value = start
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                EditorToolStrip(
                    selectedTool = null,
                    onToolClick = { clicked += it },
                    disabledTools = disabled,
                    level = levelState.value,
                    onLevelChange = { levelState.value = it },
                )
            }
        }
        compose.waitForIdle()
    }
}
