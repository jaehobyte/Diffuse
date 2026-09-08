package com.diffuse.feature.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.PortraitResult
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** work/decisions.md T79. The portrait menu is a reordering, and this is what it reorders to. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Same reason as `ToolStripLevelTest`: the strip is a `LazyRow`, so a real width is what makes
// the items past the fifth compose at all.
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class PortraitToolMenuTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- what the detector's answer decides ------------------------------

    /** T79, requirement 11: only a positive answer changes the menu. */
    @Test
    fun `only Portrait selects the portrait profile`() {
        assertEquals(ToolMenuProfile.Portrait, menuProfileFor(PortraitResult.Portrait))
        assertEquals(ToolMenuProfile.General, menuProfileFor(PortraitResult.NotPortrait))
        assertEquals(ToolMenuProfile.General, menuProfileFor(PortraitResult.Unknown))
    }

    // ---- the lists, without a screenshot ---------------------------------

    @Test
    fun `the general menu is unchanged`() {
        assertEquals(
            listOf(Tool.Light, Tool.Color, Tool.Mix, Tool.Crop, Tool.Detail),
            tools(ToolGroup.Root, ToolMenuProfile.General),
        )
        assertEquals(
            listOf(
                Tool.Select, Tool.Erase, Tool.Fill, Tool.Expand, Tool.Style, Tool.Auto, Tool.Direct,
            ),
            tools(ToolGroup.Ai, ToolMenuProfile.General),
        )
    }

    @Test
    fun `the portrait root leads with 자동 and 디테일`() {
        assertEquals(
            listOf(Tool.Auto, Tool.Detail, Tool.Light, Tool.Color, Tool.Mix, Tool.Crop),
            tools(ToolGroup.Root, ToolMenuProfile.Portrait),
        )
    }

    @Test
    fun `the portrait AI level is the general one without 자동`() {
        assertEquals(
            listOf(Tool.Select, Tool.Erase, Tool.Fill, Tool.Expand, Tool.Style, Tool.Direct),
            tools(ToolGroup.Ai, ToolMenuProfile.Portrait),
        )
    }

    /** T79's acceptance criterion: promoting 자동 must not leave a copy behind. */
    @Test
    fun `every tool still appears exactly once in each profile`() {
        ToolMenuProfile.entries.forEach { profile ->
            val shown = ToolGroup.entries.flatMap { tools(it, profile) }

            assertEquals("$profile", Tool.entries.size, shown.size)
            assertEquals("$profile", Tool.entries.toSet(), shown.toSet())
        }
    }

    /** The level items are the level's, whichever profile is bound. */
    @Test
    fun `both profiles still end the root at AI and start the AI level at back`() {
        ToolMenuProfile.entries.forEach { profile ->
            assertEquals(StripItem.OpenAi, stripItems(ToolGroup.Root, profile).last())
            assertEquals(StripItem.Back, stripItems(ToolGroup.Ai, profile).first())
        }
    }

    // ---- the strip -------------------------------------------------------

    @Test
    fun `the portrait strip puts 자동 at the head of the root`() {
        show(ToolGroup.Root, ToolMenuProfile.Portrait)

        compose.onNodeWithText("자동").assertExists()
        compose.onNodeWithText("디테일").assertExists()
        compose.onNodeWithText("AI").assertExists()
        compose.onNodeWithText("뒤로").assertDoesNotExist()
    }

    @Test
    fun `자동 is gone from the portrait AI level`() {
        show(ToolGroup.Ai, ToolMenuProfile.Portrait)

        compose.onNodeWithText("자동").assertDoesNotExist()
        compose.onNodeWithText("선택").assertExists()
        compose.onNodeWithText("지시").assertExists()
        compose.onNodeWithText("뒤로").assertExists()
    }

    @Test
    fun `the general root has no 자동`() {
        show(ToolGroup.Root, ToolMenuProfile.General)

        compose.onNodeWithText("자동").assertDoesNotExist()
        compose.onNodeWithText("라이트").assertExists()
    }

    private fun tools(level: ToolGroup, profile: ToolMenuProfile): List<Tool> =
        stripItems(level, profile).filterIsInstance<StripItem.OfTool>().map { it.tool }

    private fun show(level: ToolGroup, profile: ToolMenuProfile) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                EditorToolStrip(
                    selectedTool = null,
                    onToolClick = {},
                    level = level,
                    profile = profile,
                )
            }
        }
        compose.waitForIdle()
    }
}
