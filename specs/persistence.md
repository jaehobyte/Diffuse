# specs/persistence.md — Projects and autosave

Owner task: T17
Module: `core/data`
Depends on: edit_model.md (serialization), imaging.md §5 (file layout)

## Storage layout
```
filesDir/projects/<projectId>/
  source.<ext>      copied by ImageLoader (imaging.md)
  document.json     EditDocument, v = 1
  thumb.png         512px long edge, rendered with current ops
```
Room DB `projects.db`, table `projects`:
| column | type |
|---|---|
| id | TEXT PK |
| createdAt | INTEGER |
| updatedAt | INTEGER |
| width, height | INTEGER (post-crop, for masonry layout) |
| thumbPath | TEXT |

## API
```kotlin
interface ProjectRepository {
    fun observeAll(): Flow<List<ProjectSummary>>          // newest updatedAt first
    suspend fun create(source: SourceImage): Result<String> // returns id
    suspend fun load(id: String): Result<EditDocument>
    suspend fun save(doc: EditDocument): Result<Unit>       // writes json + thumb, updates row
    suspend fun duplicate(id: String): Result<String>
    suspend fun delete(id: String): Result<Unit>
}
```

## Autosave
- Editor VM debounces `history.current` by 2s and calls `save`. Also saves on `onStop` immediately.
- `create` is called only after a successful import; a project whose document has zero ops **and** was never saved is deleted on editor exit (no empty projects in Browse).
- Save is atomic: write `document.json.tmp`, then rename.
- Thumbnail render runs on `default` via `Renderer.preview(doc, 512)` and must not block the save of the json.

## Rules
- Room migrations: v1 has none; schema exported to `schemas/` so v2 can migrate.
- `delete` removes the folder and the row; a failure to remove the folder still removes the row and logs.
- `duplicate` copies the folder with a new id.

## Tests
- DAO: insert / observe order / delete.
- Repository on Robolectric: create → save → load round-trip equals; atomic write leaves no `.tmp` on success; delete removes files.
- Autosave: `runTest` with virtual time — two pushes within 2s → one save; `onStop` → immediate save.
- Empty-project cleanup on exit.
