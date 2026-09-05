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
While exporting: sheet closes, the editor shows the progress overlay (40% scrim, `accent` circular progress bound to `onProgress`, text "저장하는 중", tertiary "취소"). Cancel deletes the pending MediaStore entry.

## Errors
- `Io` (disk full, MediaStore failure) → snackbar `error_io`.
- Cancellation → no file, no snackbar.

## Rules
- Never write to `Environment.getExternalStoragePublicDirectory` directly; MediaStore only.
- Never request `WRITE_EXTERNAL_STORAGE`.

## Tests
- Robolectric: export 4:5 preset at 1080 → file exists in MediaStore with 864×1080; JPEG when no alpha, PNG when `hasAlpha`.
- Cancel mid-render → no MediaStore row remains.
- Settings round-trip through DataStore.
- Goldens: `export_sheet`, `export_progress_overlay`.
