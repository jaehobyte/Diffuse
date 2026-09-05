package com.diffuse.navigation

/** specs/editor_shell.md §Top bar behavior: what Back means, in priority order. */
enum class BackAction {
    /** DESIGN.md §4: a confirmation dialog, because abandoning an export is destructive. */
    ConfirmAbortExport,
    CloseExportSheet,
    CancelTool,
    LeaveEditor,
}

fun backAction(exporting: Boolean, exportSheetOpen: Boolean, toolOpen: Boolean): BackAction = when {
    exporting -> BackAction.ConfirmAbortExport
    exportSheetOpen -> BackAction.CloseExportSheet
    toolOpen -> BackAction.CancelTool
    else -> BackAction.LeaveEditor
}
