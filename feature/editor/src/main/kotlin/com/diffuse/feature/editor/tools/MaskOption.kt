package com.diffuse.feature.editor.tools

/**
 * specs/selection_tool.md §8.1: with a selection applied, every adjust sheet offers to limit
 * itself to it. Bundled so the three sheets keep one shared signature.
 */
data class MaskOption(
    /** True once the document has an active mask; the toggle is hidden otherwise. */
    val available: Boolean,
    /** Default on, so an adjustment made right after selecting lands where the user looked. */
    val maskedOnly: Boolean,
    val onMaskedOnlyChange: (Boolean) -> Unit,
) {
    companion object {
        val None = MaskOption(available = false, maskedOnly = false, onMaskedOnlyChange = {})
    }
}
