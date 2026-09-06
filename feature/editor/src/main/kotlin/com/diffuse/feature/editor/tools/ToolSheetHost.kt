package com.diffuse.feature.editor.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.color.ColorSheet
import com.diffuse.feature.editor.tools.detail.DetailSheet
import com.diffuse.feature.editor.tools.light.LightSheet
import com.diffuse.feature.editor.tools.mix.MixSheet

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
    maskOption: MaskOption = MaskOption.None,
) {
    when (selectedTool) {
        Tool.Light -> LightSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
        )
        Tool.Color -> ColorSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
        )
        Tool.Mix -> MixSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
        )
        Tool.Detail -> DetailSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
        )
        // Crop, Select, Fill, Expand and Direct carry their own state, so the route hosts them
        // alongside the editor's. Erase has no sheet: tapping it runs (generative_erase.md §5).
        Tool.Crop, Tool.Select, Tool.Erase, Tool.Fill, Tool.Expand, Tool.Direct, null -> Unit
    }
}
