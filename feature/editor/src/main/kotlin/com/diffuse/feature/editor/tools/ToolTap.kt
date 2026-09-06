package com.diffuse.feature.editor.tools

/**
 * What tapping a generative tool should do, returned rather than acted on: the 서버 설정 sheet is
 * `SelectionController`'s and there is one of it (specs/generative_erase.md §9), and only
 * `EditorViewModel` owns the history stack and the selected tool.
 *
 * One enum for all four, since T77. Each tool used to declare its own three-value copy —
 * `EraseTap`, `FillTap`, `ExpandTap` — and the ViewModel then had one `when` per tool, which a
 * fourth took past detekt's complexity ceiling. They were the same four meanings all along.
 */
enum class ToolTap {
    /** Open the tool's sheet, which is where the rest of its arguments come from. */
    Open,

    /** Run immediately: the tool needs nothing the document does not already have. */
    Run,

    /** A credential or an address is missing, which only the settings sheet can fix. */
    OpenSettings,

    /** The reason is already in the tool's own `message`; nothing more to offer. */
    Refused,
}
