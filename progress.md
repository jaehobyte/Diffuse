# progress.md

## Current

T04–T12 complete. Next: T13 Light adjustments (spec: specs/adjust_light.md).
T13 is the first task needing specs/testing.md §4's golden-image machinery, which does
not exist yet — see "Open issues for a human".

## Done

- T12 Slider component — `AdjustSlider` (4dp track, 20dp thumb, value pinned right in
  mono, centre tick, double-tap reset); 5 tests + goldens `slider_default`/`slider_zero_centered`.
- T11 Compare gesture — hold shows the source, release restores the preview; disabled
  without operations. 4 tests.
- T10 Render pipeline — `Renderer`/`CpuRenderer`, preview+base LRU caches, cancellable
  between ops, `Ops` registry, gated benchmark behind `scripts/bench.sh`. 8 tests.
- T09 Undo / redo history — `HistoryStack` with coalescing, 50-entry cap and
  `StateFlow` enablement wired into the top bar. 8 + 2 tests.
- T08 Non-destructive edit model — `EditDocument`/`Operation`/`AdjustKind`/`ImageRef`
  with accessors enforcing the specs/edit_model.md rules, plus lenient JSON. 12 tests.
- T07 Bottom sheet component — `EditSheet` (24dp corners, handle, 45% cap, pinned
  [Cancel | Apply]) plus `TertiaryPill`; 3 tests + goldens `sheet_collapsed`/`sheet_expanded`.
- T06 Editor screen shell — top bar 56dp / canvas / tool strip 72dp, edge-to-edge,
  Korean strings in strings.xml; 6 tests + golden `editor_shell_default`.
- T05 Canvas composable — `EditorCanvas` with fit/pinch/pan/double-tap, 8dp checkerboard,
  `LocalCanvasTransform`; 10 tests + goldens `canvas_fit`/`canvas_zoomed`/`canvas_transparent`.
- T04 Image loading pipeline — `ImageLoader.load(uri)` + `SourceImage`; 4096px bound,
  two-step downsample, EXIF applied to pixels, typed failures. 8 tests.
- T03 Screenshot test harness — `theme_swatches` golden (all 21 colors + 8 text styles,
  both modes), shared `ScreenshotOptions`, §7 fixtures derived from `test/kodim23.png`.
- T02 Design tokens and theme — `core/ui/theme/Tokens.kt` (21 colors, 8 text styles,
  §6 elevation), `Theme.kt` (`AppTheme(mode)`, `AppColors`, `LocalAppColors`),
  Pretendard + JetBrains Mono bundled.
- T01 Project skeleton compiles — 8 modules, Gradle 8.14.5 / AGP 8.13.2 / Kotlin 2.3.21,
  build-logic convention plugins, `dependencyGuard`, `scripts/check.sh` green offline.

## Next

T04 Image loading pipeline — unblocked. The loop can run **T04 through T12**;
T13 blocks on the still-missing `specs/adjust_light.md`.

## Decisions

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

- **The release APK is 16.06 MB, over the 15 MB budget** (specs/architecture.md §8),
  measured for the first time at T12. `isMinifyEnabled = false`, so R8 strips nothing:
  material-icons-extended and unused Compose are shipped whole. The Pretendard variable
  font is 2.81 MB of it. Enabling R8 is the obvious first move and belongs to T20/T21,
  but the budget is already breached today.

- **DESIGN.md contradicts itself on accent placement.** §1 says the accent appears in
  exactly one place per screen — "the primary action or the active-tab indicator". But §4
  requires the Export button to be a primary (accent) pill *and* the selected tool to be
  accent with a 2dp indicator. `editor_shell_default` therefore shows two accents. Needs a
  human ruling: either Export drops to a secondary pill in Edit mode, or §1 is relaxed.

- **Seven specs are still missing**, blocking T13 onward: `adjust_light`, `adjust_color`,
  `adjust_detail`, `crop`, `persistence`, `browse`, `export`. T04–T12 can run today.
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
