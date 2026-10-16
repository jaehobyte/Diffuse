package com.diffuse.feature.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * work/decisions.md D107. Whether a tool has a strip item. [Root] tools are the manual ones on
 * the strip; an [Ai] tool has **no item** — the strip's AI circle opens 지시, and these tools run
 * only as steps of the plan 지시 asks for (specs/vibe_edit.md §4). The enum stays so the sheets
 * and controllers these tools still own have a `selectedTool` to be keyed on.
 */
enum class ToolGroup { Root, Ai }

/** specs/editor_shell.md. The tool strip is driven by this list; adding a tool adds a row. */
enum class Tool(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val group: ToolGroup = ToolGroup.Root,
) {
    Light(R.string.editor_tool_light, Icons.Rounded.LightMode),
    Color(R.string.editor_tool_color, Icons.Rounded.Palette),

    /** specs/adjust_hsl.md §6: 혼합 is a colour tool and sits beside 색. */
    Mix(R.string.editor_tool_mix, Icons.Rounded.Colorize),
    Crop(R.string.editor_tool_crop, Icons.Rounded.Crop),
    Detail(R.string.editor_tool_detail, Icons.Rounded.Tune),

    /**
     * D107: from here on, no strip item. 선택 went first (D104); now 지우기, 채우기, 확대, 화질 개선,
     * 스타일 and 멀티샷 are reached only as plan steps, or not at all.
     */
    Erase(R.string.editor_tool_erase, Icons.Rounded.AutoFixHigh, ToolGroup.Ai),

    /** specs/generative_fill.md §6: the two generative region tools sit together. */
    Fill(R.string.editor_tool_fill, Icons.Rounded.AutoAwesomeMotion, ToolGroup.Ai),

    /** specs/outpaint.md §6: the one tool that makes the canvas bigger, after the two that fill. */
    Expand(R.string.editor_tool_expand, Icons.Rounded.OpenInFull, ToolGroup.Ai),

    /**
     * specs/topaz_enhancement.md §1: 화질 개선, after 확대 — the one that adds pixels to the same
     * frame, beside the one that adds frame. Every existing tool keeps its relative order.
     */
    Enhance(R.string.editor_tool_enhance, Icons.Rounded.HighQuality, ToolGroup.Ai),

    /**
     * specs/tool_groups.md §2 puts 스타일 at the AI level and §9's open decision 2 says why: the
     * tool itself calls nothing (style_match.md §4), but its 컬러 매칭 half does, and the level is
     * named for where things are rather than for what they cost.
     */
    Style(R.string.editor_tool_style, Icons.Rounded.AutoAwesomeMosaic, ToolGroup.Ai),

    /**
     * specs/multishot.md §2: pixels from other photographs, so an AI-level tool. Appended before
     * 지시 so every existing tool keeps its relative order.
     */
    MultiShot(R.string.editor_tool_multishot, Icons.Rounded.BurstMode, ToolGroup.Ai),

    /** specs/vibe_edit.md §3, D107: what the strip's AI circle opens; it can reach the others. */
    Direct(R.string.editor_tool_direct, Icons.Rounded.AutoAwesome, ToolGroup.Ai),
}

/**
 * What the strip shows: the manual tools, then the one item that is not a `Tool` — the AI circle,
 * which opens 지시 (D107). Tapping it selects no tool of its own.
 */
sealed interface StripItem {
    data class OfTool(val tool: Tool) : StripItem

    /** The AI circle, at the end of the strip. DESIGN.md §4: the gradient circle, white glyph. */
    data object OpenAi : StripItem
}

/**
 * The strip binds the root tools, so adding a manual tool is still one enum entry. Pure, so "a
 * plan-only tool has no item" is a test and not a screenshot.
 *
 * work/decisions.md D092: one menu for every photograph — no portrait profile reorders or filters
 * it, so the enum's order is the only order.
 */
fun stripItems(): List<StripItem> =
    Tool.entries.filter { it.group == ToolGroup.Root }.map(StripItem::OfTool) + StripItem.OpenAi

@StringRes
internal fun StripItem.labelRes(): Int = when (this) {
    is StripItem.OfTool -> tool.labelRes
    StripItem.OpenAi -> R.string.editor_tool_ai
}

internal fun StripItem.icon(): ImageVector = when (this) {
    is StripItem.OfTool -> tool.icon
    StripItem.OpenAi -> Icons.Rounded.AutoAwesome
}
