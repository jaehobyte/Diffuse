package com.diffuse.feature.editor

import com.diffuse.core.imaging.history.HistoryStack

/**
 * tasks.md T22: drops every operation as a single undoable step. No coalesce key, so it
 * never merges into a slider group, and no confirmation dialog — undo covers it.
 *
 * The active selection goes with its `Mask` op: a document whose `activeMaskId` names no op
 * in its list does not load again (edit_model.md, `referencesResolve`).
 */
fun HistoryStack.resetToOriginal() {
    val document = current.value
    if (document.operations.isEmpty()) return
    commitCoalesce()
    push(document.copy(operations = emptyList(), activeMaskId = null))
}
