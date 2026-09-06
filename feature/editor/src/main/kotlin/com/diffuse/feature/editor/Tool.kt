package com.diffuse.feature.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * specs/tool_groups.md §3. Which level of the strip a tool appears at.
 *
 * It replaces `Tool.isAi`, which existed only to drive the 6dp accent dot. §2 moved that dot up
 * to the AI parent — inside the AI level every tool is an AI tool, so the dot marks nothing there
 * — and two spellings of one fact is how the two come to disagree.
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

    Select(R.string.editor_tool_select, Icons.Rounded.HighlightAlt, ToolGroup.Ai),
    Erase(R.string.editor_tool_erase, Icons.Rounded.AutoFixHigh, ToolGroup.Ai),

    /** specs/generative_fill.md §6: the two generative region tools sit together. */
    Fill(R.string.editor_tool_fill, Icons.Rounded.AutoAwesomeMotion, ToolGroup.Ai),

    /** specs/outpaint.md §6: the one tool that makes the canvas bigger, after the two that fill. */
    Expand(R.string.editor_tool_expand, Icons.Rounded.OpenInFull, ToolGroup.Ai),

    /** specs/auto_enhance.md §6: the one AI tool that answers in adjustments rather than pixels. */
    Auto(R.string.editor_tool_auto, Icons.Rounded.AutoFixNormal, ToolGroup.Ai),

    /** specs/vibe_edit.md §3: last in the strip, because it can reach any of the others. */
    Direct(R.string.editor_tool_direct, Icons.Rounded.AutoAwesome, ToolGroup.Ai),
}

/**
 * specs/tool_groups.md §3. Which level the strip is bound to, and how to change it. One parameter
 * rather than two, the shape `MaskOption` and `CanvasPointTaps` already use — `EditorScreen` is at
 * detekt's method-length limit and every line of its signature counts against it.
 */
data class ToolLevelState(
    val level: ToolGroup = ToolGroup.Root,
    val onChange: (ToolGroup) -> Unit = {},
)

/**
 * specs/tool_groups.md §2. What the strip shows at one level: its tools, plus the one item that
 * moves between levels. Neither of those two is a `Tool` — tapping them selects nothing.
 */
sealed interface StripItem {
    data class OfTool(val tool: Tool) : StripItem

    /** The AI parent, at the end of the root level. Carries the 6dp accent dot (§2). */
    data object OpenAi : StripItem

    /** The ← item, at the head of the AI level. `editInk`, not accent. */
    data object Back : StripItem
}

/**
 * §3: the strip binds one level's tools, so adding a tool is still one enum entry.
 *
 * Pure, so §7's "every `Tool` appears at exactly one level" is a test and not a screenshot.
 */
fun stripItems(level: ToolGroup): List<StripItem> {
    val tools = Tool.entries.filter { it.group == level }.map(StripItem::OfTool)
    return when (level) {
        ToolGroup.Root -> tools + StripItem.OpenAi
        ToolGroup.Ai -> listOf(StripItem.Back) + tools
    }
}

@StringRes
internal fun StripItem.labelRes(): Int = when (this) {
    is StripItem.OfTool -> tool.labelRes
    StripItem.OpenAi -> R.string.editor_tool_ai
    StripItem.Back -> R.string.editor_tool_back
}

internal fun StripItem.icon(): ImageVector = when (this) {
    is StripItem.OfTool -> tool.icon
    StripItem.OpenAi -> Icons.Rounded.AutoAwesome
    StripItem.Back -> Icons.AutoMirrored.Rounded.ArrowBack
}
