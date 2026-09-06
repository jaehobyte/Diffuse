# work/decisions.md — why the code is the way it is

Split out of `progress.md`, which CLAUDE.md keeps under 150 lines. Newest task first.

Every entry records a choice that the specs did not make for us, or one where the code and a
spec disagreed and the code won. Read the entry for a task before changing that task's code:
most of these are the second attempt, not the first.

## Decisions

### T44 — `data object` for the argument-less steps, and `DEFAULT_PLAN` on the companion

specs/vibe_edit.md §7 writes `object Erase` / `object CutOut`; the code uses `data object`, which
is what `AppError` already does for its argument-less cases and what makes `PlanStep.Erase` print
as itself in a failed assertion. No behavioural difference.

`FakePlanProvider.DEFAULT_PLAN` is public on the companion rather than private, because the tests
that assert "the second call fell back to the default" would otherwise have to re-spell the plan
and drift from it. `planCount` mirrors `FakeEraseProvider.eraseCount`; nothing else was added, so
`next` and `failNext` each override exactly one call and nothing accumulates between tests.

`core/ai/build.gradle.kts` took `implementation(projects.core.imaging)` and not `api`, as T44 says.
`AdjustKind` does appear in `EditPlanProvider`'s signature, but `feature:editor` — the only
consumer — already declares `core:imaging` itself, so nothing needs the edge transitively.

### T43 — `EraseTap` is returned, not acted on

§9 wants a missing key to open the 서버 설정 sheet, and that sheet is `SelectionController`'s:
`showSettings` lives in `SelectionState`, and `EditorRoute` renders it from there. Giving
`EraseController` its own copy would mean two owners of one sheet and two things to clear on
cancel. So `onToolTapped` returns `EraseTap.Run` / `OpenSettings` / `Refused` and
`EditorViewModel` — the one object holding both controllers — does the routing.

`SelectionController.setSettingsVisible(visible)` replaced `dismissSettings()` rather than sitting
beside a new `requestSettings()`: adding a function put the class at 21 and detekt's
`TooManyFunctions` threshold is 20. One function that opens and closes reads better than two
anyway, and `explain()` now goes through it too.

`onToolClick` hit `CyclomaticComplexMethod` at the same time, so the erase branch moved into a
private `onEraseTapped`.

### T43 — the no-selection row is reported before the no-key row

§9's table lists `activeMaskId == null` first, and it is the only ordering signal the spec gives.
It means someone with neither a selection nor a key is told to select first and only then to paste
a key — two steps. The alternative (key first) reads as more helpful but is invented, and the tool
is greyed for both reasons either way, so the table order stands.

### T43 — `"blocked:"` is duplicated rather than imported

`GeminiEraseClient` is `internal` to `core:ai`, so `feature:editor` cannot see its
`BLOCKED_PREFIX`. That is the module boundary working: §6 makes the prefix part of the
`AppError.Invalid` contract, and a feature reading the contract is right where a feature reaching
into the HTTP client would be wrong. The constant is `private` in `EraseController` with the spec
reference next to it.


### T42 — the provider takes `DispatcherProvider`, which §7's snippet does not show

§7 sketches `GeminiEraseProvider(client, settings)` but then requires "all of it on
`DispatcherProvider.io`, with `ensureActive()` before the network call". The bitmap work —
downscale, `WhiteFill`, JPEG compress — happens in the provider, not the client, so the provider
needs the dispatcher itself. Three constructor parameters, not two.

It also uses `dispatchers.default` for the scope `availability`'s `stateIn` runs in, which is what
lets a test make that mapping settle synchronously by handing back `Dispatchers.Unconfined`.

### T42 — `availability` is a mapped `StateFlow`, and the scope is never cancelled

`GeminiEraseProvider` is a `@Singleton` with no lifecycle, so the `CoroutineScope` backing
`stateIn` lives as long as the process — the same lifetime the flow it exposes has. `Eagerly` so
`availability.value` is correct before anyone collects, which is what `EraseController` reads on
its first frame.

### T42 — the "whitened bytes on the wire" test asserts near-white, not `0xFFFFFFFF`

The image is sent as JPEG q90, so the decoded bytes are white to within a quantization step and
the boundary column is visibly off (0xFFF5FFFF in the first run). Asserting exact white would
have been asserting the absence of lossy compression, which is not what this test is for. It
checks every channel is ≥ 235 inside the mask, and that the unmasked half is still recognizably
the original blue — which is what actually proves `WhiteFill` sits in the path.

### T42 — `MaskPng.kt` went with the other three

§13 makes it conditional on nothing referencing it afterwards. After `Sam3EraseClient` and
`Sam3EraseProvider` were removed, a repo-wide grep found its own declaration and nothing else, so
it was deleted rather than moved to `gemini/`. The Gemini path sends no mask over the wire at all.


### T41 — a pixel loop, not a `SRC_IN` composite

§4 offers either. The loop wins on readability: the composite needs a scratch bitmap, a `Paint`
with a `PorterDuffXfermode`, and a `Canvas`, and the reader then has to reason about what
`SRC_IN` does to a `ALPHA_8` source. The loop says "if the mask is set, write white". It runs
once per erase on a bitmap of at most 1024px, so the cost is irrelevant.

The image is read once with `getPixels` and written once with `setPixels`; only the *mask* is
read per pixel, because `ALPHA_8` does not survive `getPixels` usefully.

### T41 — the size mismatch throws rather than returning null

§7 step 1 already turns a mismatched mask into `Invalid` at the provider, so by the time
`WhiteFill` is called the sizes agree. The `require` is therefore a programming-error guard, not
an error path, and `IllegalArgumentException` says that where a nullable return would not.


### T40 — one `Part` shape for both directions, and `explicitNulls = false`

A request part carries `inlineData` **or** `text`; a response part carries the same two. Two
classes would have identical fields, so there is one `Part` with both nullable. That only works
because the `Json` sets `explicitNulls = false`: otherwise the request body would carry
`"text": null` alongside the image, which the API rejects as an empty part. A test asserts the
unused field is absent rather than null, because nothing else would catch that setting being lost.

### T40 — `GeminiConfigSource` rather than injecting `GeminiSettings` into the client

`GeminiSettings` hard-codes `DEFAULT_BASE_URL`, which is the point (§8: no user-editable host).
The test still has to reach `MockWebServer`, so the client takes a `fun interface
GeminiConfigSource` and the test hands it `{ GeminiConfig(key, server.url) }`. Exactly the seam
`Sam3ConfigSource` already is, for exactly the same reason.

### T40 — the client rejects a blank key before the wire, though availability already does

§7 makes `availability` blank-key-aware, so in the app this branch is unreachable. It is here
anyway because `Sam3Client` has the same guard for the base URL, and because a client that
silently sends `x-goog-api-key: ` would fail as a 401 — an error that reads like a *wrong* key
rather than a missing one.


### T39 — the Gemini key rides in `SelectionController`, and two tests took a constructor arg

`work/tasks.md` lists T39's `touches` as `core/ai/gemini`, `Sam3SettingsSheet.kt`,
`EditorRoute.kt` and `strings.xml`. Wiring the key from the sheet to `GeminiSettings` cannot fit
inside that list: `EditorRoute` reads the sheet's values out of `EditorUiState` and has no way to
reach a `@Singleton` itself. Three files outside the list changed, each minimally:

- `EditorAi` gains `geminiSettings`. It is already "the editor's whole AI surface in one
  injectable", so a second credential belongs there rather than in a new one.
- `SelectionController` takes it and `saveSettings` becomes three-arg. The controller already
  owns the 서버 설정 sheet's lifecycle (`showSettings`, `dismissSettings`), and generative_erase.md
  §8 makes that one sheet serve both providers — so the alternative was a second owner for the
  same sheet. `SelectionState.geminiApiKey` exists for the same reason: it is the state the sheet
  renders from.
- `SelectionToolTest` and `GenerativeEraseToolTest` construct `EditorAi` directly, so they take
  the new argument. Mechanical only; no assertion was changed except the one in
  `saveSettings closes the sheet and re-probes`, which now also asserts the key was stored.

The name `Sam3SettingsSheet` is now wrong — it is the 서버 설정 sheet for two providers. Renaming
it would touch `EditorRoute`, the test tags and `SelectSheetTest`, for no behaviour, so it stays.

### T39 — the key field is masked but its value is still readable to a test

`PasswordVisualTransformation` changes `EditableText` to bullets and sets the `Password`
semantics flag, but leaves `InputText` as the real value — so `onNodeWithText("AIza-old")` still
matches. The test asserts on `Password` and on `EditableText` instead of on the absence of a text
node, because the absence is not true and asserting it would only be true by accident.


### T30/T41 — the third device run: it works

Verified on an SM-S948N against the real model, over the public endpoint: a tap on the dog's coat
segmented the coat, and the phrase `"dog"` segmented the whole animal. Server log confirms
`POST /v1/images 201` then `segment/points 200` and `segment/text 200`.

One more defect, found only because the run got that far:

- **A rejected token had no way to be corrected.** `/healthz` needs no auth, so a bad token sits
  behind a perfectly healthy server: `everReady` was true, the T40 rule called it a transient
  blip and showed a snackbar. `Unauthorized` is a configuration problem by definition, so it
  joins `Invalid` in always opening the settings sheet. The rule is now one question — *can the
  user fix this by editing the settings?* — rather than three overlapping conditions.
- `select_unavailable_unauthorized` became dead when that branch went, and was deleted.

### T30/T40 — the second device run: an unreachable default with no way out

The tool was greyed and there was no way to change the address. Three things had to line up:

- **The shipped default was `http://10.0.2.2:8080`** — the *emulator's* alias for its host, which
  resolves to nothing on a real phone. `local.properties` now defaults to `127.0.0.1:8080`, what
  `adb reverse` gives a USB-attached device.
- **The settings sheet was reachable only while the URL was blank.** A wrong address and a server
  that is merely down are the same `Unavailable` to the app, so a non-blank wrong URL greyed the
  tool permanently. The rule is now whether the backend has *ever* answered: never → the settings
  are probably wrong, open the sheet; once → a blip, say so and re-probe.
- **A fresh install wipes the in-app override.** `firstInstallTime == lastUpdateTime` on the
  device confirmed the settings entered before v0.3.1 were gone, which is why something that had
  demonstrably worked stopped.

Also: **the build-time token stays empty**, because `BuildConfig` is compiled into the APK and the
APK is published. A token belongs in the settings sheet.

### T30/T39 — the first device run

Three defects, found from the server's access log after the tool went permanently grey. The log
showed six `POST /v1/images` with no prompts and no `DELETE`s between them, then three `429`s —
the server's `upload_rate` is 6 per 60 seconds, so the limiter fired exactly as configured.

- **A cancelled `open` was leaking a session.** Aborting the upload does not un-create what the
  server already made from it; it only loses the id, leaving an orphan in one of the backend's
  four slots for the full 120s TTL. The call is no longer cancellable — it runs to completion and
  a session nobody wants any more is `close`d as soon as its id is known.
- **`open` guarded on `session != null` only.** An upload takes seconds, and `session` is not set
  until it finishes, so every tap inside that window started another one. It now guards on
  `preparing` too.
- **A 429 latched `availability` to `Unavailable` for good.** Rate limits, dropped connections and
  a restarting server all arrive as `Unavailable` and all three pass, but nothing re-probed except
  a settings change — so the tool stayed grey with no way back. Tapping a greyed tool now probes.

`FakeSegmentationProvider` registers its session *before* its delay, which is what a real backend
does; without that the leak was invisible to tests, and the first two versions of the regression
test passed for the wrong reason.

- **The app logged nothing.** The whole first run had to be debugged from the *server's* access
  log, because a failed call surfaced as a Korean snackbar and nothing else. `Sam3Client` now logs
  the route and the mapped status through `core:common`'s `Logger`, as architecture.md §9 always
  required.
- **Nobody ever called `close()`.** selection_tool.md §6 says leaving the editor releases the
  session; `EditorViewModel.onLeave` now does, before the autosave so a slow write cannot hold it.

### T38

- **`EraseController` owns run → save → commit, not `EditorViewModel`.** detekt flagged the
  ViewModel at 20 functions, a 7-argument constructor and a complex condition, all from this one
  task. The tool is one object now, the way the selection tool is; the ViewModel hands it the two
  things it cannot reach (the repository and the history stack) as lambdas.
- **`EditorAi` bundles the four AI dependencies.** The ViewModel's constructor is about the
  screen, not about the model boundary.
- **The result is stored, and export composites it.** generative_erase.md §7: re-running the
  model at export resolution would produce different pixels than the user approved, so the stored
  result is scaled instead.
- **Nothing touches the document until the bitmap is on disk.** A failed write leaves the sheet
  and the selection exactly as they were, rather than a document pointing at a file that is not
  there.

### T36

- **A phrase merges into what is on screen, not into the committed base.** The first version
  merged into `SelectionState.base`, which silently discarded a live point run — the user would
  tap an object, type a word, and watch the tap disappear. A phrase *ends* the run; it does not
  ignore it. Caught by "a phrase adds to what points already selected".
- **A text prompt gets the progress overlay; a point prompt does not.** prompt_input.md §4 asks
  for progress and a cancel while a phrase is in flight, while selection_tool.md §5 says a point
  prompt keeps the previous mask visible with no spinner. `phraseBusy` is what tells them apart.
- **The bar clears only on a successful merge.** A failure keeps the text so the user can retry
  without retyping, and 찾지 못했어요 keeps it so they can edit the word.

### T35

- **`SpeechInput` is not an `ai_provider.md` provider.** It has no `Availability` flow and no
  suspend entry point, because it is a streaming device service rather than a request/response
  model. Forcing it into that shape would have meant a fake `Availability` and a `suspend fun`
  that never returns.
- **Permission lives in the composable, not the ViewModel.** The launcher needs a composition,
  and the ViewModel has no business knowing about Android grants.
- **A second denial hides the mic for the session.** Asking again on every tap is the nagging
  DESIGN.md §7 rules out, and there is nothing else the app can do about it.

### T34

- **Neither icon is accent.** DESIGN.md §1 allows a sheet one accent at rest and that is its
  Apply pill; a prompt bar taking it would make one sheet commit differently from every other.
  Send earns its weight from the enabled state and the IME Done key.
- **`core:ui` gained material-icons-extended.** DESIGN.md §7 mandates one rounded icon set and
  the core set has no Mic. The library is already in the APK through `:feature:editor`, so this
  costs nothing.
- **`BasicTextField` with a hand-rolled placeholder**, not `OutlinedTextField`: Material's field
  brings its own container, label and indicator, none of which DESIGN.md §4 wants.

### T33

- **배경 지우기 is a secondary action, not a primary pill.** selection_tool.md §8.2 asks for a
  primary pill, but DESIGN.md §1 allows the sheet one accent at rest and that is its Apply. Two
  accent pills would also make two different commits look equally primary.
- **`EditDocument.hasAlpha` reads the source's file extension.** The document holds an `ImageRef`
  and no `SourceImage`, but `DefaultProjectRepository` writes the source as `.png` exactly when it
  had alpha, so the extension *is* that flag. Recorded here because it is a real coupling.
- **Cut-outs render before the crop.** A cut-out is about pixels, like the adjustments; the crop
  is geometry and stays last (render.md).
- **`select_sheet_open` was re-recorded again**, for the same reason as T31: the task adds a row
  to that sheet. Named in the task.

### T32

- **A masked adjustment is computed whole, then blended back.** The op never learns that masks
  exist, so `Ops.kt` stays the single place the maths lives and the GPU port (D03) is unaffected.
- **`MaskBlend` is written as a lerp even though v2 masks are binary**, because that is the
  contract selection_tool.md §8.1 states and feathering then costs nothing here.
- **A dangling mask reference makes the adjustment whole-frame rather than dropping it.** Losing
  the adjustment entirely would be the more surprising of the two, and `referencesResolve` already
  refuses to load a document whose *active* mask is missing.

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
