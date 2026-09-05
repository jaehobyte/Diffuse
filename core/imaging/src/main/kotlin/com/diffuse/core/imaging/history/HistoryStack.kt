package com.diffuse.core.imaging.history

import com.diffuse.core.imaging.model.EditDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** specs/history.md: fifty entries hold operations and a path, a few KB in total. */
const val HISTORY_MAX_ENTRIES = 50

/**
 * Undo/redo over document states, with slider drags collapsed into one step
 * (specs/history.md).
 *
 * Not thread-safe: it is owned by one editor screen and touched from the main thread.
 */
class HistoryStack(
    initial: EditDocument,
    private val maxEntries: Int = HISTORY_MAX_ENTRIES,
) {

    private val entries = ArrayDeque<EditDocument>().apply { addLast(initial) }
    private var index = 0

    /** The key of the group still open for coalescing, or null once committed. */
    private var openCoalesceKey: String? = null

    private val _current = MutableStateFlow(initial)
    val current: StateFlow<EditDocument> = _current.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * Appends [next] and clears the redo stack. When [coalesceKey] matches the group still
     * open, [next] replaces the top entry instead, so a slider drag is one undo step.
     * A different key implicitly commits the previous group.
     */
    fun push(next: EditDocument, coalesceKey: String? = null) {
        if (coalesceKey != null && coalesceKey == openCoalesceKey) {
            entries[index] = next
        } else {
            while (entries.lastIndex > index) entries.removeLast()
            entries.addLast(next)
            index = entries.lastIndex
            if (entries.size > maxEntries) {
                entries.removeFirst()
                index = entries.lastIndex
            }
            openCoalesceKey = coalesceKey
        }
        publish()
    }

    /** Ends the open coalescing group: the next push starts a new entry. */
    fun commitCoalesce() {
        openCoalesceKey = null
    }

    fun undo() {
        if (index == 0) return
        index--
        commitCoalesce()
        publish()
    }

    fun redo() {
        if (index == entries.lastIndex) return
        index++
        commitCoalesce()
        publish()
    }

    private fun publish() {
        _current.value = entries[index]
        _canUndo.value = index > 0
        _canRedo.value = index < entries.lastIndex
    }
}
