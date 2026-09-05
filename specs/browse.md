# specs/browse.md — Browse home & import

Owner tasks: T18 (grid), T19 (import)
Module: `feature/browse`
Design: DESIGN.md §4 Image tile, §5 Layout, Browse mode palette

## Screen
- Top bar 56dp, white, no title. Left: app mark (simple text "Editor" in `headingMd` for v1, no logo). Right: none in v1.
- Body: `LazyVerticalStaggeredGrid`, 2 columns (3 at ≥ 600dp), 8dp gap, 16dp horizontal padding. Tiles use `width/height` from `ProjectSummary` for aspect.
- Tile: 16dp corners, `surfaceCard` background, thumbnail via Coil with crossfade off. Below: relative time in `bodySm`, `inkSecondary` ("3분 전", "어제").
- FAB: none. The primary CTA is a `primary` pill "새 프로젝트" pinned at the bottom center, 48dp tall, 16dp above the nav bar.
- Empty state: centered `headingXl` "첫 사진을 열어보세요" + the same primary pill.

## Interaction
- Tap tile → `EditorRoute(projectId)`.
- Long-press tile → tile scales to 0.97 and shows two `iconCircle`s at top-right: duplicate, delete. Tap elsewhere dismisses. Delete asks a destructive confirmation.
- Pull-to-refresh: none (Flow is live).

## Import (T19)
- CTA → `PickVisualMedia(ImageOnly)`. No storage permission requested; the Photo Picker does not need it.
- Result URI → `ImageLoader.load` → `ProjectRepository.create` → navigate to the editor. Show a full-screen `40%` scrim with circular progress during load; the CTA is disabled meanwhile.
- `Unsupported` / `TooLarge` / `Io` → snackbar with the mapped string; stay on Browse.
- Share-sheet intent (`ACTION_SEND image/*`) opens the app directly into import with the given URI.

## Tests
- Goldens: `browse_grid` (6 fake projects, mixed aspects), `browse_empty`, `browse_tile_actions`.
- UI: long-press shows actions; delete confirmation → repository.delete called.
- Import: fake picker returning a URI → `create` called → navigation effect emitted; fake loader returning `Unsupported` → snackbar effect.
- 600dp qualifier golden: `browse_grid_wide` (3 columns).
