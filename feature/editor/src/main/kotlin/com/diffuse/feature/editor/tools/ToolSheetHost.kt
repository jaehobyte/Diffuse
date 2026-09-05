package com.diffuse.feature.editor.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.color.ColorSheet
import com.diffuse.feature.editor.tools.light.LightSheet

/**
 * Maps the selected tool to its sheet. specs/architecture.md §5.2 wants adding a tool to be
 * one entry here plus one tool definition, with no switch statements anywhere else.
 */
@Composable
fun ToolSheetHost(
    selectedTool: Tool?,
    document: EditDocument,
    onValueChange: (AdjustKind, Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (selectedTool) {
        Tool.Light -> LightSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier,
        )
        Tool.Color -> ColorSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier,
        )
        // T15 and T16 add Crop and Detail.
        Tool.Crop, Tool.Detail, null -> Unit
    }
}
