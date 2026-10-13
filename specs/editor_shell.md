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
│ ○빛 ○색 ○혼합 ○자르기 ○디테일 ○선택 ○지우기 ○채우기 ○확대 ○지시 │
└──────────────────────────────┘
```
The top bar and tool strip are light (`editSurface`, black `editInk`); only the canvas surround is
dark (`editBackground`), and text or guides over it use `canvasOverlayInk` (DESIGN.md §1, §2). The
strip's surface runs under the transparent navigation bar, and both system bars use dark icons
regardless of the OS dark setting (DESIGN.md §8).

Sheets (`EditSheet`) slide up over the tool strip, max 45% height. **A sheet covers the strip, so
while one is open the strip must not receive taps that land on the sheet** — the [취소 | 적용] row
sits directly over the strip's leftmost items. See T57. This covers the whole sheet, including its
padding and the gap between 취소 and 적용: `EditSheet` is one hit target, and only its own controls react.

The strip scrolls (`LazyRow`) and has since it passed four items, so a new tool needs no
reordering; it needs one `Tool` entry and one `ToolSheetHost` branch (architecture.md §5.2).

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
enum class Tool { Light, Color, Crop, Detail, Select, Erase, Direct }
```
Single `EditorViewModel`, MVI-style: UI sends `EditorIntent`, VM reduces to `EditorUiState`.

## Top bar behavior
- Back: if a sheet is open → Cancel the sheet. Else if exporting → destructive confirmation. Else autosave (T17) and pop.
- Undo/Redo: call `HistoryStack`. Disabled while a sheet is open (sheet has its own Cancel).
- Reset: drops every operation in one undoable step (`history.push(doc.copy(operations = emptyList()))`, no coalesce key). No confirmation — undo covers it. Disabled when `operations.isEmpty()`. The canvas refits afterwards, since removing a Crop changes the dimensions.
- Compare: press-and-hold. `comparing = true` on down, `false` on up/cancel. Shows `source` at preview size. Disabled when `operations.isEmpty()`.
- Export pill: opens the export sheet (T20). Disabled while a tool sheet is open.

## Tool strip behavior
- Tapping a tool sets `selectedTool` and opens its sheet. Tapping the selected tool again closes it (= Cancel)
  where that tool is exposed; while a sheet covers the strip it is not reachable, and 취소 or Back closes the sheet.
- Selected state: accent icon + label + 2dp indicator.
- `LazyRow`; selected item scrolls into view. Seven tools do not fit on a phone, so the row scrolls — which is why it was a `LazyRow` from T11, when four did fit.
- New tools are **appended**. The order is the order they shipped in, not a ranking; reordering would move the item under a user's thumb for no stated reason.

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
