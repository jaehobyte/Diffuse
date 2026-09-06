# progress.md

## Current

**T22-T24 complete.** `scripts/check.sh` green offline. The v1.1 fix phase is done.

**v2 was re-planned on 2026-09-06** (user decision). On-device EdgeTAM is dropped in favour of the
server-side SAM 3 service at `~/sam3-server`, and the backlog gains a prompt bar with voice input
and a generative eraser. `work/tasks.md` is now T26-T38 in four phases, with the human
prerequisites listed at the top of that file rather than as a task. ADR-007 and ADR-008 are struck
in architecture.md §10; ADR-009 and ADR-010 replace them.

Specs are written and consistent: `ai_provider.md`, `segmentation.md`, `selection_tool.md`
(rewritten), `prompt_input.md`, `generative_erase.md` (new), plus amendments to `edit_model.md`,
`architecture.md` (§2, §6, §8, §9, §10) and `DESIGN.md` (§1 accent ruling, §4 prompt bar).

**T26-T31 done.** The selection tool works end to end against the SAM 3 service, including
add/subtract merging. Next is T32, masked adjustments.

## Done

- T31 Accumulated merging — `MaskOps.merged/union`, a [추가 | 빼기] chip row, and an undo that
  drops a point inside a run and one whole merge once the run is empty. 12 tests + golden
  `select_mask_merged`; `select_sheet_open` re-recorded for the new row.

- T30 "선택" tool — `SelectionController` owns the whole tool (availability, session, points,
  mask, settings sheet); `EditorViewModel` only commits it. Canvas `gestureMode = SelectPoint`
  with normalized taps, `MaskOutline` tracing the scrim and the outline from one `Region` path,
  the AI dot and greyed-tool state in the strip, and the SAM 3 settings sheet T28 deferred.
  20 tests + goldens `select_sheet_open`, `select_mask_preview`.

- T29 `Operation.Mask` — the op, `EditDocument.activeMaskId` / `withMask` / `referencesResolve`,
  JSON, `MaskIo` (ALPHA_8 ↔ PNG), `Renderer.resolveMask` with a 2-entry cache, and
  `ProjectRepository.saveMask` writing `mask_<id>.png`. 21 tests.

- T28 `Sam3SegmentationProvider` — one live session plus the bytes that opened it, so §5's
  expiry replay (re-upload once, repeat the prompt) never reaches the caller. `Sam3Settings`
  on SharedPreferences with `local.properties` defaults through `:core:ai`'s own BuildConfig,
  `Sam3ImageCodec` (2048px, JPEG 90 → 75), and `AiModule`. 19 tests.

- T27 `Sam3Client` — OkHttp + kotlinx.serialization over the five SAM 3 routes, `Sam3Outcome`
  with `SessionExpired` as its own case, and `MaskCodec`. 17 MockWebServer tests, localhost only.

- T26 `core:ai` module — `SegmentationProvider` (`open`/`byPoints`/`byText`/`close`) and
  `EraseProvider` behind `Availability`, plus the two fakes in `src/testShared/kotlin` so
  `:feature:editor` tests can compile them. 15 tests. `settings.gradle.kts`, the dependencyGuard
  module map and `:feature:editor` all gained the `:core:ai` edge.

- T24 Live rotate / straighten preview — `OverlayTransform` in the canvas rotates the drawn
  bitmap about the image centre with no `Renderer` pass; quarter turns swap the fitted size.
  2 transform tests + goldens `crop_live_rotate_15` / `crop_live_rotate_90`.

- T23 Crop preset aspect — the geometry was right; `EditorRoute` fed it a constant 4:3.
  `CropState` now carries `sourceAspect` (from the bare-source preview) and flips it on odd
  quarter turns. `presetAspectMatchesInPixels` covers five presets x both orientations.

- T22 Reset to original — `RestartAlt` icon between Redo and Compare, `resetToOriginal()`
  as one uncoalesced history step, viewport zeroed so the canvas refits. 1 test +
  `editor_shell_default` re-recorded.

- T21 Navigation and polish — Hilt graph, Browse → Editor → Export sheet, autosave on
  back, destructive confirmation while exporting, predictive back.
- T20 Export — format/size/preset sheet, render→crop→downscale pipeline, MediaStore
  writer with IS_PENDING, progress overlay with cancel. 8 tests + 2 goldens.
- T19 Import from Photo Picker — `BrowseImport`, `BrowseRoute` with `PickVisualMedia`,
  40% scrim while decoding, `AppError` → Korean snackbar. 6 tests.
- T18 Browse home — staggered 2/3-column masonry, long-press actions with a destructive
  confirmation, Korean relative times, empty state. 7 tests + 4 goldens.
- T17 Project persistence — Room `projects` table with exported schema, atomic document
  writes, thumbnails, `ProjectAutosave` with a 2s debounce. 14 tests.
- T16 Detail adjustments — Sharpen (separable unsharp mask) and Vignette, `DetailSheet`;
  6 tests + 2 render goldens + `detail_sheet_open`. Every `AdjustKind` now has real maths.
- T15 Crop and rotate — rotate-then-crop render, `CropGeometry` auto-shrink, overlay with
  handles and thirds grid, preset/straighten/90° sheet. 15 tests + 4 goldens.

_T01–T14 trimmed per CLAUDE.md (keep the last 10). Their decisions are still in ## Decisions below, and the full text is in git history._

## Next

T32 adjustments limited to the selection. T34 (`PromptBar`) is still free-standing.

## Decisions

### T31

- **Undo means one step back, whichever kind.** Inside a point run it drops the last point and
  re-segments; with the run empty it takes back one whole merge. selection_tool.md §4 and §10 each
  describe one of the two, and the user should not have to know which mode they are in.
- **Switching the mode commits the run.** Otherwise a subtract-mode tap would have to choose
  between appending a background point to the existing prompt and starting a new one, and the two
  mechanisms would fight over the same gesture.
- **`select_sheet_open` was re-recorded.** T31 adds the mode row to that sheet, so the golden
  could not stay as it was; the task now names it, per the T22/T23 precedent.

### T30

- **The tool is a `SelectionController`, not more `EditorViewModel`.** detekt caught the ViewModel
  at 29 functions and `EditorScreen` at 69 lines, which was the right signal: the selection tool
  has its own lifecycle (the session outlives the sheet) and its own undo stack, and mixing them
  made both harder to read. Raising the thresholds would have hidden a real design problem.
- **The scrim and the outline are one path.** `MaskOutline` unions the mask's row runs into a
  `Region` and takes its boundary; the scrim is that path clipped out of the image rect, the
  outline is the same path stroked. They cannot drift apart, and stroking after the screen
  transform keeps the outline 1dp at any zoom.
- **Prompt points are stored normalized.** They then survive a zoom, a re-render at a different
  resolution, and the upload's own downscale, so nothing has to be re-mapped.
- **`Sam3Client` is `@Provides`-d, not `@Inject`-constructed.** Keeping it out of the graph's
  public surface means no feature can reach past `SegmentationProvider` to the wire.
- **An unconfigured provider opens the settings sheet, not a snackbar.** A snackbar saying "set
  the server address" with no way to set it is a dead end.

### T29

- **A `mask` node without a `maskRef` is dropped, not fatal.** `EditDocumentJsonTest` already used
  `{"type":"mask","id":"m","brush":"soft"}` as its *unknown type* fixture, written before the op
  existed. Making the decoder lenient keeps that test honest and matches edit_model.md's rule that
  one unreadable operation must not cost the whole document. The user is told instead by
  `referencesResolve()` failing, which turns into `Unsupported` on load.
- **`MaskIo` writes an ARGB_8888 PNG whose alpha carries the mask**, not an ALPHA_8 one.
  `Bitmap.compress` does not write ALPHA_8 usefully; ARGB round-trips losslessly everywhere.
- **`Operation.Mask` stores no prompts.** A merged selection has no single reproducing prompt
  (selection_tool.md §4), so storing one would be a lie that re-editing would have to honour.

### T28

- **`refresh()` was added to `SegmentationProvider`.** specs/segmentation.md §7 wants availability
  probed when the tool opens and when settings change, never polled. Without an entry point that
  means either a background scope in a `@Singleton` (a leak, and untestable) or a health check
  bolted onto `open()` (a wasted round trip on the critical path). One suspend method is the
  smaller change; ai_provider.md §3 and §4 record it.
- **`SegSession` carries the *caller's* image size, not the uploaded one.** Prompts are normalized,
  so nothing needs the upload's resolution but the mask rescale, and that is the provider's own
  business. Keeping the upload size private is what lets §3's downscale change without any caller
  noticing.
- **A rejected prompt does not flip `availability`; an unreachable or unauthenticated backend
  does.** §7 says one bad request is not a dead server. `AppError.Invalid` therefore leaves the
  tool enabled, while `Unavailable` and `Unauthorized` grey it.
- **`BuildConfig` lives on `:core:ai`, not `:app`.** `:app` depends on `:core:ai`, so the reverse
  read is impossible, and BuildConfig fields are per-module. `gradle.properties` disables the
  feature globally; this module turns it back on for itself.
- **The settings sheet moved to T30.** T28's `touches` named `app`, but a sheet with no screen to
  open it from is dead code, and the first screen that needs it is the selection tool.

### T24

- **`OverlayTransform` splits the quarter turns from the straighten**, because `CropOp`
  does: the turns change the image's shape (so the canvas refits to the swapped size), the
  straighten rotates inside those bounds (so its corners are clipped and the rect
  auto-shrinks). One combined angle could not drive both.
- **The bitmap is drawn into an axis-swapped rect and then rotated onto the image rect.**
  Rotating the fitted rect itself would leave the drawn image at the wrong aspect.
- **`touches` named `feature/editor/canvas` and `.../tools/crop`,** but the transform has to
  reach the canvas through `EditorScreen` and be built in `EditorRoute`; both were edited.
- **`recordRoborazziDebug --tests '*CropGoldenTest*'` also rewrites `crop_overlay.png`.**
  T24 does not name that golden, so it was restored from git; `verifyRoborazziDebug` then
  passed against the committed version, confirming the change is inside the threshold.

### T23

- **The root cause was the call site, not `CropGeometry`.** The task guessed "aspect
  enforced in normalised space without multiplying by the source aspect"; the maths already
  multiplied. `EditorRoute` passed a hardcoded `CANVAS_ASPECT = 4f / 3f`, so on a 3000x4000
  source the red test reported `Square gave 0.5625, expected 1.0` — and 16:9 gave ~1:1,
  exactly the reported symptom.
- **`CropState` owns `sourceAspect`**, so `withPreset`/`straightened` can no longer be
  handed the wrong number. The ViewModel reads it off the bare-source preview, whose shape
  is the source's shape.
- **`imageAspect` inverts on odd quarter turns**, because `CropOp` normalises `rect`
  against the post-quarter-turn canvas. Without it a preset chosen after a 90° turn would
  reintroduce the same class of bug.
- **`touches` named only `feature/editor/tools/crop`**, but the constant lived in
  `EditorRoute` and the aspect had to come from `EditorViewModel`; both were edited, since
  the bug is unfixable inside the crop package alone.
- **`rotated()` still leaves the rect and the preset chip alone** after a 90° turn
  (specs/crop.md §Interaction asks for both). Untouched from T15 — out of T23's scope.

### T22

- **The reset icon sits in the centre group, right after Redo.** DESIGN.md §4 puts the
  history controls in the centre and Compare/Export on the right; "between Redo and
  Compare" is satisfied either way, and grouping it with undo/redo keeps the right side to
  the two actions §4 names. `Icons.Rounded.RestartAlt` over `history`, which reads as
  "version history" rather than "start over".
- **Reset zeroes the viewport.** `RefitOnSizeChange` only refits a viewport the user has
  not zoomed, so `onReset` resets `CanvasViewport()` in `EditorScreen` to guarantee the
  refit the task asks for when a Crop is dropped.
- **`resetToOriginal()` is an extension on `HistoryStack`**, not a private VM method, so
  the UI test drives the same code the ViewModel does instead of restating it.
- **CLAUDE.md forbids editing `specs/*.md`, but T22's `touches` explicitly allows
  appending one row to the Top bar section.** Took the more specific instruction and
  appended a single bullet to specs/editor_shell.md §Top bar behavior.

### Stack (T01)

- **AGP 8.13.2 / Kotlin 2.3.21 / Gradle 8.14.5 / compileSdk 36.** AGP 9 was rejected:
  its DSL changes are what an unattended loop gets wrong, and CLAUDE.md forbids the loop
  from editing root gradle files, so a mismatch forces a block rather than a fix.
  `targetSdk 36` is a documented deviation from specs/architecture.md §2 "latest stable" (37),
  which AGP 8.13.2 cannot compile against.
- **Version ceilings this forces.** Each pin is the newest that works on this line:
  `hilt 2.58` (2.59+ demands AGP 9.0), `composeBom 2026.06.01` (2026.08.00 ships Compose
  1.12.0, needing AGP 9.1 + compileSdk 37), `coreKtx 1.18.0`, `lifecycle 2.10.0`,
  `navigationCompose 2.9.8`, `hiltNavigationCompose 1.3.0`, `coil 3.4.0`.
- **Tripwire: Kotlin 2.3.21 is exactly at Hilt 2.58's metadata ceiling.** Hilt 2.58 reads
  Kotlin metadata only up to 2.3. coil 3.5.0+ is built with Kotlin 2.4 and breaks the
  Hilt processor. Do not bump Kotlin to 2.4.x without also moving to AGP 9 + Hilt 2.59+.
- **`core:common` is a pure JVM module** (specs/architecture.md §3 allows it no Android deps),
  so its Hilt bindings must live in a `@Module` in `app`. It registers a
  `testDebugUnitTest` alias, without which check.sh would silently skip its tests.
- **`dependencyGuard` is a custom root task**, not the Dropbox plugin, because §4
  describes module-graph rules while that plugin locks dependency version lists.

### Test harness (T01, T03)

- **Robolectric offline.** It fetches `android-all` from Maven at *test runtime*, which
  `--offline` forbids. The artifact is pinned, resolved through a Gradle configuration,
  synced to `build/robolectric-deps`, and reached via `robolectric.offline=true`.
  Each Android module also pins `sdk=36` in `robolectric.properties` — Robolectric
  otherwise falls back to `minSdk` (26) on library modules and demands an API-26 jar.
- **`junit-platform-launcher` is mandatory** or JUnit 5 discovery dies on unaligned
  platform jars. JUnit 5 runs with the vintage engine so Robolectric/Roborazzi JUnit 4
  tests share one platform (specs/testing.md §3).
- **Goldens are declared as test-task inputs.** Without that Gradle marks the test task
  UP-TO-DATE and `verifyRoborazziDebug` passes against a deleted or edited golden — the
  exact hole testing.md §5 forbids. Verified by injection; see ## Current.
- **`changeThreshold` lives in build-logic, not the Roborazzi extension**, which exposes
  no such knob in 1.73. The value is a system property set once for every Compose module
  and `ScreenshotOptions` is its only reader, failing loudly if it is unset.
- **Golden path is fixed in `ScreenshotOptions.goldenPath()`**, because the Roborazzi
  Gradle plugin owns `roborazzi.output.dir` and overrode attempts to set it.

### Design tokens (T02)

- **Pretendard as a single variable font.** DESIGN.md §3 mandates it and testing.md §5
  needs it bundled for deterministic goldens. Measured compressed-in-APK: variable
  2.82 MB vs 4.06 MB for four static weights, plus JetBrains Mono Medium at 127 KB.
  Total ≈ 2.95 MB of the 15 MB budget (specs/architecture.md §8) — re-measure at T20.
  `FontVariation` needs `@OptIn(ExperimentalTextApi::class)`; safe because the Compose
  version is pinned in a frozen catalog.
- **Where DESIGN.md is silent:** `mono` line height follows `label` at 1.3; the six
  styles with no stated tracking get an explicit `letterSpacing = 0.sp`, since unset
  resolves to `Unspecified` (NaN) and is neither assertable nor deterministic.
- **`AppColors` carries only mode-dependent roles**; brand and semantic colors are
  identical in both modes so they default to `Tokens`. Edit-mode `surfaceSecondary`
  maps to `editSurfaceRaised` per DESIGN.md §4. MaterialTheme gets a colorScheme but no
  typography — §2 says M3 is for primitives only.

### Fixtures (T03)

- **All §7 fixtures are derived from `test/kodim23.png`** (user decision), generated by
  `scripts/make_fixtures.py` so the derivation stays reviewable. `photo_512.png` is a
  512×288 crop over a 512×96 row of reference patches, because testing.md §4 requires
  skin tone, sky, deep shadow and neutral gray, none of which kodim23 contains.
  `transparent_256.png` and `corrupt.jpg` cannot come from kodim23 at all and are
  synthesised.

### Phase 0 follow-up

- **All specs moved to `specs/`**, and `ARCHITECTURE.md` became `specs/architecture.md`.
  tasks.md cites `specs/...` for every task but the files lived in `docs/specs/`, and
  CLAUDE.md forbids editing tasks.md text — so the filesystem had to move, not the task
  list. Side effect, and a deliberate one: the specs are now covered by CLAUDE.md's
  "never modify any `*.md` under `specs/`" hard limit.
- **`core:common` was written as Phase 0 work.** specs/architecture.md §3 assigns it
  `Result`, dispatchers, logging and ids, and §9 defines the `AppError` cases — but no
  task in tasks.md lists `core/common` under `touches`, so no loop iteration may create
  them. T04, T08, T10 and T17 all depend on those types; without this the loop blocks on
  its first task. Contents kept to exactly the §3 list, nothing more.
  `DispatcherProvider` deliberately has no `main`: §5.4 reserves the main thread for
  Compose, and exposing it here would invite core modules to touch it.
- **`core:common` exposes coroutines with `api`**, since `DispatcherProvider` returns
  `CoroutineDispatcher`. The JVM convention now applies `java-library` for that, and
  no longer picks dependencies for modules it does not know about.
- **`lint { ignoreTestSources = true }`.** Lint's Kotlin analysis crashes on the
  Hilt-generated test classes in `:app` ("this is a bug in lint or one of the libraries
  it depends on"). Main-source detection is unaffected and was re-verified by injection.

### T05

- **Kotlin has no `testFixtures` compilation under AGP 8.13**: enabling `testFixtures`
  creates only `compileDebugTestFixturesJavaWithJavac`, so Kotlin sources there are never
  compiled and consumers see an unresolved reference. `ScreenshotOptions` therefore lives
  in `core/ui/src/testShared/kotlin`, which `ComposeConventionPlugin` adds to every
  Compose module's unit-test source set. Still one definition, as testing.md §5 demands.
- **`detectTransformGestures` cannot drive a hoisted viewport.** Every pointer event reads
  the viewport as of the last *composition*, so a burst of events within one frame all
  scale from the same stale value and most of the gesture is lost — a pinch of 15× landed
  as 1.04×. `detectCanvasTransformGestures` seeds a working copy once per gesture and
  accumulates locally. Touch slop still eats the opening of a gesture, so the clamp tests
  pinch twice.
- **`CanvasBounds` bundles the canvas and image sizes.** It started as a detekt
  `LongParameterList` fix but reads better: every viewport calculation needs both.


_(none)_

## Open issues for a human

- **The crop tool previews the *cropped* image, not the full source.** specs/crop.md says
  opening 자르기 refits to the un-cropped source; the ViewModel just renders the current
  document, so an existing Crop is baked into what the overlay sits on. Harmless until
  T24 made the rotation visible; the fix is to render the document minus its Crop while
  the sheet is open.

- **Compare in the editor route is not wired to the ViewModel.** `EditorScreen` owns the
  hold state and swaps to `source`, which the VM renders, but `onCompareChange` is a no-op
  at the route level.

- **specs/export.md asks for DataStore; T20 used `SharedPreferences`.** The catalog is
  frozen by CLAUDE.md and has no DataStore entry. Either add one and migrate
  `ExportSettingsStore`, or amend the spec.
- **The MediaStore write is not covered by a test.** `ImageStore` is an interface and the
  pipeline is tested through a fake; the `MediaStoreImageStore` implementation itself needs
  a device or a Robolectric shim that does not exist yet.

- **The release APK is 16.06 MB, over the 15 MB budget** (specs/architecture.md §8 — the budget
  returned to 15 MB when ADR-008 was struck, so this is live again),
  measured for the first time at T12. `isMinifyEnabled = false`, so R8 strips nothing:
  material-icons-extended and unused Compose are shipped whole. The Pretendard variable
  font is 2.81 MB of it. Enabling R8 is the obvious first move and belongs to T20/T21,
  but the budget is already breached today.

- ~~DESIGN.md contradicts itself on accent placement.~~ **Resolved 2026-09-06.** §1 now reads
  "at most once per surface at rest" — top bar, tool strip and frontmost sheet each get one —
  "plus one transient active state" (the progress spinner, the mic while listening). This is what
  §4 and `editor_shell_default` were already doing. Consequence for v2: a prompt bar's send icon
  is never accent; the sheet's accent stays on its Apply pill.

- ~~Seven specs are still missing.~~ Stale — all of them landed with T13–T21.
- **`specs/imaging.md` is a draft written by Claude, not reviewed.** It fixes
  `MAX_LONG_EDGE_PX = 4096`, a two-step downsample, EXIF applied to pixels, and the
  error mapping. Read it before starting the loop; it is frozen once T04 begins.
- **specs/render.md and specs/architecture.md disagree on error style.** render.md throws
  `RenderException.MissingSource`; §9 mandates `Result` + `AppError`. architecture.md
  says it wins on conflict, and imaging.md follows it — render.md is left untouched.
- **`photo_12mp.jpg` is 1.42 MB, not the "~3MB" testing.md §7 states.** It is 4000×3000
  with EXIF orientation 6 as required; it compresses well because it is upscaled from a
  768×512 source. Say so if the byte size itself matters to a test.
- **`ScreenshotOptions` lives in `core:ui/src/test`.** The first feature-module golden
  (T05) needs it too and cannot see it from there — promote it to `testFixtures` then.
- **testing.md §4's golden-image machinery does not exist yet**: `GoldenAssert.kt`,
  `golden_manifest.txt`, `GoldenInventoryTest`. They belong in `core:imaging`, outside
  T03's `touches`. First needed by T13.
- **`fixtures/`, `scripts/check.sh` and `core:common` were authored as Phase 0 human
  deliverables**, matching tasks.md's "done by a human before the loop starts"; all sit
  outside the `touches` lists of the tasks that specify them.
