package com.diffuse.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** T21 done-when: back with an export running is a destructive confirmation. */
class BackActionTest {

    @Test
    fun `an export in flight wins over everything else`() {
        assertEquals(
            BackAction.ConfirmAbortExport,
            backAction(exporting = true, exportSheetOpen = true, toolOpen = true),
        )
    }

    @Test
    fun `the export sheet closes before the tool sheet`() {
        assertEquals(
            BackAction.CloseExportSheet,
            backAction(exporting = false, exportSheetOpen = true, toolOpen = true),
        )
    }

    @Test
    fun `an open tool sheet cancels rather than leaving`() {
        assertEquals(
            BackAction.CancelTool,
            backAction(exporting = false, exportSheetOpen = false, toolOpen = true),
        )
    }

    @Test
    fun `with nothing open, back leaves the editor`() {
        assertEquals(
            BackAction.LeaveEditor,
            backAction(exporting = false, exportSheetOpen = false, toolOpen = false),
        )
    }
}
