package com.diffuse.feature.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/** specs/editor_shell.md. The tool strip is driven by this list; adding a tool adds a row. */
enum class Tool(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** DESIGN.md §4: AI tools carry a 6dp accent dot. No "AI" badge text. */
    val isAi: Boolean = false,
) {
    Light(R.string.editor_tool_light, Icons.Rounded.LightMode),
    Color(R.string.editor_tool_color, Icons.Rounded.Palette),

    /** specs/adjust_hsl.md §6: 혼합 is a colour tool and sits beside 색. */
    Mix(R.string.editor_tool_mix, Icons.Rounded.Colorize),
    Crop(R.string.editor_tool_crop, Icons.Rounded.Crop),
    Detail(R.string.editor_tool_detail, Icons.Rounded.Tune),
    Select(R.string.editor_tool_select, Icons.Rounded.HighlightAlt, isAi = true),
    Erase(R.string.editor_tool_erase, Icons.Rounded.AutoFixHigh, isAi = true),

    /** specs/generative_fill.md §6: the two generative region tools sit together. */
    Fill(R.string.editor_tool_fill, Icons.Rounded.AutoAwesomeMotion, isAi = true),

    /** specs/outpaint.md §6: the one tool that makes the canvas bigger, after the two that fill. */
    Expand(R.string.editor_tool_expand, Icons.Rounded.OpenInFull, isAi = true),

    /** specs/vibe_edit.md §3: last in the strip, because it can reach any of the others. */
    Direct(R.string.editor_tool_direct, Icons.Rounded.AutoAwesome, isAi = true),
}
