# progress.md

## Current

T01 Project skeleton compiles — **complete, uncommitted** (interactive Phase 0, not a loop iteration).
Verified: `scripts/check.sh` exits 0 offline (~10s); `assembleDebug` produces app-debug.apk;
3 tests execute and pass; `dependencyGuard` proven to fail on injected violations.

## Done

- T01 Project skeleton compiles — modules `app`, `core:{common,imaging,ui,data}`,
  `feature:{browse,editor,export}`; Gradle 8.14.5 / AGP 8.13.2 / Kotlin 2.3.21;
  `scripts/check.sh` green offline. Not yet committed (awaiting review).

## Next

T02 Design tokens and theme (deps: T01).

## Decisions

- **T01 / stack**: AGP 8.13.2, Kotlin 2.3.21, Gradle 8.14.5, KSP 2.3.11, Compose BOM
  2026.08.00. The terminal 8.x AGP line was chosen over AGP 9.4.0: AGP 9 carries
  breaking DSL changes, and CLAUDE.md forbids the loop from editing root gradle
  files, so a DSL mismatch would force a block rather than a fix.
- **T01 / targetSdk**: `compileSdk`/`targetSdk` pinned to **36**, not 37.
  ARCHITECTURE.md §2 says "targetSdk latest stable"; AGP 8.13.2 caps compileSdk at 36.
  Documented deviation — revisit if the project moves to AGP 9.
- **T01 / core:common is a JVM module**: ARCHITECTURE.md §3 says core:common has no
  Android dependencies, so it uses `diffuse.jvm.library`, not the android-library
  convention. Consequence: its Hilt bindings (`DispatcherProvider`) must live in a
  `@Module` in `app`, since Hilt needs an Android module.
- **T01 / `testDebugUnitTest` alias**: that task name is Android-only, so `core:common`
  registers an alias depending on `test`. Without it `scripts/check.sh` would silently
  skip every core:common unit test.
- **T01 / `dependencyGuard`**: implemented as a custom root Gradle task encoding the
  ARCHITECTURE.md §3 module map and the §4 rules (no feature→feature, nothing depends
  on `:app`, no Compose/Hilt/Room in `core:imaging`, no Room in `core:ui`). Chosen over
  the Dropbox dependency-guard plugin, which locks dependency *version lists* rather
  than the module graph that §4 actually describes. No new dependency, no network.
- **T01 / JUnit 5 + vintage**: `de.mannodermaus.android-junit5` 2.0.1 with the JUnit
  vintage engine, so specs/testing.md §3's JUnit 5 unit tests and the JUnit 4-only
  Robolectric/Roborazzi tests run on one platform in the same module.
- **T01 / Robolectric offline**: Robolectric fetches its `android-all` jar from Maven at
  *test runtime*, which `check.sh --offline` forbids. `android-all-instrumented` is
  pinned in the catalog, resolved through a Gradle configuration, synced to
  `build/robolectric-deps`, and `robolectric.offline=true` +
  `robolectric.dependency.dir` point at it.
- **T01 / `scripts/check.sh` authored in Phase 0**: T01's `done when` requires it to exit
  0, but `touches` lists only gradle files and T03 owns `scripts/`. Since tasks.md marks
  all of Phase 0 as human work, `check.sh` was written once here, verbatim from
  specs/testing.md §2, and is frozen from now on. T01 therefore also had to wire detekt,
  Roborazzi and dependencyGuard so all five task names resolve.
- **T01 / Roborazzi `changeThreshold` deferred**: specs/testing.md §5 requires it set once
  in `build.gradle.kts`. T01 only applies the plugin so `verifyRoborazziDebug` exists
  (it passes trivially with zero goldens); T03 owns the threshold and the first golden.

- **T01 / version ceilings forced by the AGP 8.13.2 lane.** The priming run rejected the
  newest release of several libraries; each pin below is the newest version that works
  with AGP 8.13.2 + compileSdk 36 + Kotlin 2.3.21. Moving to AGP 9 would lift all of them.
  - `hilt` **2.58** — 2.59+ hard-fails: "only compatible with AGP 9.0.0 or higher".
  - `composeBom` **2026.06.01** (Compose 1.11.4) — 2026.08.00 ships Compose 1.12.0, which
    requires AGP 9.1.0 and compileSdk 37.
  - `coreKtx` **1.18.0**, `lifecycle` **2.10.0**, `navigationCompose` **2.9.8**,
    `hiltNavigationCompose` **1.3.0** — newer builds require compileSdk 37 / AGP 9.1.
  - `coil` **3.4.0** — coil3 3.6.x drags `org.jetbrains.compose:1.12.0` in transitively,
    which forces AndroidX Compose to 1.12.0 regardless of the BOM; coil3 3.5.0 avoids that
    but is compiled with Kotlin 2.4.0, and Hilt 2.58 reads Kotlin metadata only up to 2.3.
    Kotlin 2.3.21 therefore sits exactly at Hilt 2.58's ceiling — **do not bump Kotlin to
    2.4.x without also moving to AGP 9 + Hilt 2.59+.**
- **T01 / `junit-platform-launcher` is mandatory.** Without it JUnit 5 discovery dies with
  "OutputDirectoryCreator not available ... unaligned versions of junit-platform-engine and
  junit-platform-launcher". Added as `testRuntimeOnly` in the shared test config.
- **T01 / `robolectric.properties` per Android module.** Robolectric defaults to the
  manifest's targetSdk, which library modules do not set, so it fell back to `minSdk` (26)
  and demanded an API-26 `android-all` jar that offline mode does not have. Each Android
  module now pins `sdk=36` (matching the pinned `android-all-instrumented` artifact) and
  `graphicsMode=NATIVE` (specs/testing.md §5).
- **T01 / three smoke tests kept deliberately** (`SmokeJunit5Test`, `SmokeRobolectricTest`,
  `MainActivityLaunchTest`). They are beyond T01's literal scope, but with zero test sources
  `check.sh` passed offline while the test *toolchain* was entirely unproven — the first
  test the loop wrote would have failed on an unprimed `kotlin-scripting-compiler-embeddable`.
  `MainActivityLaunchTest` is also how T01's "app launches to an empty Compose screen" was
  verified: this machine has no device and no `/dev/kvm`, so no emulator is possible.
  Delete them if you would rather T02/T03 own the first tests.

## Attempts

_(none)_

## Open issues for a human (not blocking T01)

- `work/tasks.md` references spec files that do not exist:
  `specs/imaging.md` (T04), `adjust_light.md` (T13), `adjust_color.md` (T14),
  `crop.md` (T15), `adjust_detail.md` (T16), `persistence.md` (T17),
  `browse.md` (T18, T19), `export.md` (T20). **T04 blocks the loop immediately.**
- Task specs say `specs/architecture.md`; the file is `ARCHITECTURE.md` at the repo root,
  and the existing specs live under `docs/specs/`, not `specs/`.
- `fixtures/` (specs/testing.md §7) is a human deliverable for T03 and does not exist yet.
  The only fixture present is `test/kodim23.png`.
