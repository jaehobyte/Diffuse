# specs/history.md — Undo / redo

Owner task: T09
Module: `core/imaging/history`

## Purpose
One-step undo/redo over document changes, with slider drags collapsed into a single step.

## API
```kotlin
class HistoryStack(initial: EditDocument, maxEntries: Int = 50) {
    val current: StateFlow<EditDocument>
    val canUndo: StateFlow<Boolean>
    val canRedo: StateFlow<Boolean>

    fun push(next: EditDocument, coalesceKey: String? = null)
    fun undo()
    fun redo()
    fun commitCoalesce()
}
```

## Semantics
- `push` appends `next` and clears the redo stack.
- **Coalescing**: if `coalesceKey` equals the key of the last push and `commitCoalesce()` has not been called since, `next` replaces the top entry. Key convention: `"adjust:<Kind>"`, `"crop"`. The editor calls `commitCoalesce()` on slider release (`onChangeFinished`) and on sheet Apply.
- Cap: beyond `maxEntries`, drop the oldest.
- `undo` at bottom / `redo` at top are no-ops.

## Memory
Entries hold `EditDocument` only (ops + a path). Fifty entries are a few KB; no eviction logic needed in v1.

## Edge cases
- Coalesce key changes mid-drag: implicit commit, new group starts.
- Undo/redo while an export is running: allowed for the document, but export continues rendering the document it started with.

## Tests
- Push/undo/redo ordering.
- Redo cleared on new push.
- Coalesce replaces top; `commitCoalesce` then push appends.
- Cap at 50 drops the oldest and `canUndo` stays true.
