package com.diffuse.feature.editor

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/** specs/editor_shell.md. The tool strip is driven by this list; adding a tool adds a row. */
enum class Tool(@StringRes val labelRes: Int, val icon: ImageVector) {
    Light(R.string.editor_tool_light, Icons.Rounded.LightMode),
    Color(R.string.editor_tool_color, Icons.Rounded.Palette),
    Crop(R.string.editor_tool_crop, Icons.Rounded.Crop),
    Detail(R.string.editor_tool_detail, Icons.Rounded.Tune),
}
