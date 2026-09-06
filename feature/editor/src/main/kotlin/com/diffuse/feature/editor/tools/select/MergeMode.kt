package com.diffuse.feature.editor.tools.select

import androidx.annotation.StringRes
import com.diffuse.feature.editor.R

/**
 * specs/selection_tool.md §4. Whether the next prompt's result joins the selection or is taken
 * out of it. Without this the only way to undo an over-eager selection would be to start over.
 */
enum class MergeMode(@StringRes val labelRes: Int) {
    Add(R.string.select_mode_add),
    Subtract(R.string.select_mode_subtract),
}
