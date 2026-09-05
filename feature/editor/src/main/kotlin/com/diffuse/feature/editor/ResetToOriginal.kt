package com.diffuse.feature.editor

import com.diffuse.core.imaging.history.HistoryStack

/**
 * tasks.md T22: drops every operation as a single undoable step. No coalesce key, so it
 * never merges into a slider group, and no confirmation dialog — undo covers it.
 */
fun HistoryStack.resetToOriginal() {
    val document = current.value
    if (document.operations.isEmpty()) return
    commitCoalesce()
    push(document.copy(operations = emptyList()))
}
