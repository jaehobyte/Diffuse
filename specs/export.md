# specs/export.md — Export

Owner task: T20
Module: `feature/export`, `core/imaging/render` (full render), `core/imaging/load` (encode helpers)
Design: DESIGN.md §4 Bottom sheet, State display

## Sheet
Opened from the top-bar export pill. Title `export_title` ("내보내기").
1. Format: segmented chips [JPEG | PNG]. Default JPEG; PNG auto-selected when the document `hasAlpha`.
2. Size: chips [원본 | 2048 | 1080] — long edge in px. "원본" = working resolution (≤ 4096, imaging.md).
3. Preset: chips [없음 | 인스타 4:5 | 스토리 9:16]. A preset applies a center crop on top of the document's crop for this export only — it does not modify the document.
4. Bottom row: Cancel | primary "저장".

Settings persist in DataStore (last used format/size).

## Pipeline
1. `Renderer.full(doc, onProgress)` on `default`.
2. Apply preset center-crop, then downscale to the chosen long edge with bilinear filtering.
3. Encode: JPEG quality 92 with the source EXIF copied except orientation (which is now baked in) and thumbnail; PNG with alpha.
4. Write via `MediaStore.Images` into `Pictures/<AppName>/` with `IS_PENDING` until complete. Filename `IMG_<yyyyMMdd_HHmmss>.<ext>`.
5. Emit success effect → snackbar "저장했어요" with action "열기" (ACTION_VIEW on the URI).

## Progress UI
While exporting: sheet closes, the editor shows the progress overlay (40% scrim, `accent` circular progress bound to `onProgress`, text "저장하는 중" and tertiary "취소" in `canvasOverlayInk`). Cancel deletes the pending MediaStore entry.

## Errors
- `Io` (disk full, MediaStore failure) → snackbar `error_io`.
- Cancellation → no file, no snackbar.

## Rules
- Never write to `Environment.getExternalStoragePublicDirectory` directly; MediaStore only.
- Never request `WRITE_EXTERNAL_STORAGE`.

## 고해상도 결과 저장 (topaz_enhancement.md §5)
The one exception to the pipeline above. A Topaz result is a finished file at up to 256 MP; running it
through `Renderer.full` would cut it to the 4096px working size. `MediaStoreFileStore.copy` instead:
1. Inserts a `Pictures/<AppName>/IMG_<yyyyMMdd_HHmmss>.<ext>` row with `IS_PENDING = 1`.
2. Copies the file's bytes unchanged (no decode, no re-encode, no EXIF edit).
3. Reads the stored image's bounds back; anything but the expected W×H deletes the row and fails.
4. Clears `IS_PENDING`. Failure or cancellation at any step deletes the row.
The editor reaches it only through `ResultFileSaver`, bound in `app` (architecture.md §4.1). The
normal export sheet, its sizes and its 4096px bound are unchanged.

## Tests
- Robolectric: export 4:5 preset at 1080 → file exists in MediaStore with 864×1080; JPEG when no alpha, PNG when `hasAlpha`.
- Cancel mid-render → no MediaStore row remains.
- Settings round-trip through DataStore.
- Goldens: `export_sheet`, `export_progress_overlay`.
- `MediaStoreFileStoreTest`: a 6000px-wide file is stored byte for byte at its size; a size
  mismatch or a missing file leaves no row.
