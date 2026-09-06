package com.diffuse.feature.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.HighlightAlt
import androidx.compose.material.icons.rounded.LightMode
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
    Crop(R.string.editor_tool_crop, Icons.Rounded.Crop),
    Detail(R.string.editor_tool_detail, Icons.Rounded.Tune),
    Select(R.string.editor_tool_select, Icons.Rounded.HighlightAlt, isAi = true),
    Erase(R.string.editor_tool_erase, Icons.Rounded.AutoFixHigh, isAi = true),
}
