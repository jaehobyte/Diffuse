package com.diffuse.feature.editor.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.tools.color.ColorSheet
import com.diffuse.feature.editor.tools.detail.DetailSheet
import com.diffuse.feature.editor.tools.enhance.TopazGroup
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
    /** specs/topaz_enhancement.md §1: opens the shared Topaz sheet on a purpose group. */
    onAiShortcut: ((TopazGroup) -> Unit)? = null,
) {
    when (selectedTool) {
        Tool.Light -> LightSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
            onAiShortcut?.let { { it(TopazGroup.Light) } },
        )
        Tool.Color -> ColorSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
            onAiShortcut?.let { { it(TopazGroup.Color) } },
        )
        Tool.Mix -> MixSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
        )
        Tool.Detail -> DetailSheet(
            document, onValueChange, onValueChangeFinished, onCancel, onApply, modifier, maskOption,
            onAiShortcut?.let { { it(TopazGroup.Enhance) } },
        )
        // Crop, Fill, Expand, 화질 개선, Style, Direct and 멀티샷 carry their own state, so the
        // route hosts them alongside the editor's. Erase has no sheet at all: tapping it runs
        // (generative_erase.md §5).
        Tool.Crop, Tool.Erase, Tool.Fill, Tool.Expand, Tool.Enhance, Tool.Style,
        Tool.Direct, Tool.MultiShot,
        null,
        -> Unit
    }
}
