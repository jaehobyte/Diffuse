# specs/editor_shell.md — Editor screen shell

Owner tasks: T06, T11
Module: `feature/editor`
Design: DESIGN.md §4 (Top bar, Tool strip, State display), §5, §8

## Purpose
The frame hosting canvas, tools, and sheets. Owns editor-level state (selected tool, open sheet, viewport) and wires history + renderer to the canvas.

## Layout (portrait, v1 locks portrait)
```
┌──────────────────────────────┐ 56dp  Top bar (editSurface)
│ ←     ↶  ↷        ◐  [내보내기] │
├──────────────────────────────┤
│         EditorCanvas         │ fills remaining
├──────────────────────────────┤ 72dp  Tool strip (editSurface)
│   ○Light   ○Color   ○Crop   ○Detail   │
└──────────────────────────────┘
```
Sheets (`EditSheet`) slide up over the tool strip, max 45% height.

## State
```kotlin
data class EditorUiState(
    val preview: ImageBitmap?,
    val canUndo: Boolean, val canRedo: Boolean,
    val selectedTool: Tool?,      // null = no sheet open
    val comparing: Boolean,
    val exporting: Boolean,       // shows the progress overlay (T20)
    val snackbar: String?,
)
enum class Tool { Light, Color, Crop, Detail }
```
Single `EditorViewModel`, MVI-style: UI sends `EditorIntent`, VM reduces to `EditorUiState`.

## Top bar behavior
- Back: if a sheet is open → Cancel the sheet. Else if exporting → destructive confirmation. Else autosave (T17) and pop.
- Undo/Redo: call `HistoryStack`. Disabled while a sheet is open (sheet has its own Cancel).
- Reset: drops every operation in one undoable step (`history.push(doc.copy(operations = emptyList()))`, no coalesce key). No confirmation — undo covers it. Disabled when `operations.isEmpty()`. The canvas refits afterwards, since removing a Crop changes the dimensions.
- Compare: press-and-hold. `comparing = true` on down, `false` on up/cancel. Shows `source` at preview size. Disabled when `operations.isEmpty()`.
- Export pill: opens the export sheet (T20). Disabled while a tool sheet is open.

## Tool strip behavior
- Tapping a tool sets `selectedTool` and opens its sheet. Tapping the selected tool again closes it (= Cancel).
- Selected state: accent icon + label + 2dp indicator.
- `LazyRow`; selected item scrolls into view. Four tools fit without scrolling on phones; keep the row scrollable for v2 additions.

## Sheet lifecycle
- Open: snapshot the document as `sheetBaseline`.
- Slider drag: `history.push(doc, coalesceKey)` on every change; `commitCoalesce()` on `onChangeFinished`.
- Cancel: VM restores `sheetBaseline` and truncates history back to the entry that was on top at open time (no new history entry).
- Apply: `commitCoalesce()`, close the sheet.
- System back with a sheet open = Cancel.

## Edge cases
- Stale preview race: each render request is tagged with the document hash; results not matching `current` are ignored.
- Config change: VM survives; viewport is `rememberSaveable`.
- Process death: document id in `SavedStateHandle`; VM reloads from persistence (T17). Before T17, reload from the source only.

## Tests
- UI test: each tool opens/closes its sheet; Cancel restores baseline; Apply keeps changes.
- UI test: undo/redo enablement reflects the stack; disabled with a sheet open.
- UI test: compare hold shows source, release restores.
- Goldens: `editor_shell_default`, `editor_shell_sheet_open`.
