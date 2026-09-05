# specs/testing.md — Testing strategy and the definition of green

Owner tasks: T03 (harness), every task (uses it)
Module: all; scripts in `scripts/`

## 1. Purpose
Define what "green" means so the loop can judge itself, and fix the rules for each kind of test so the agent cannot pass by weakening them.

## 2. `scripts/check.sh` — the only verdict

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew --offline --quiet \
  lint detekt \
  testDebugUnitTest \
  verifyRoborazziDebug \
  dependencyGuard
```
- Exits 0 only if every step passes. No step may be skipped by an environment variable.
- `--offline` is mandatory: the loop must never resolve new dependencies.
- Wall-clock budget: < 8 minutes on the loop machine. If it grows past that, split slow tests into `scripts/bench.sh`, do not skip them.
- `scripts/bench.sh` runs Macrobenchmark and render benchmarks. Not part of green; run by a human weekly.

## 3. Test layers

| Layer | Where | Tool | Runs in `check` |
|---|---|---|---|
| Unit (pure Kotlin) | `core:*/src/test` | JUnit5, Turbine, kotlinx-coroutines-test | yes |
| Golden image (pixel math) | `core:imaging/src/test` | JUnit5 + fixture PNGs | yes |
| Screenshot (Compose) | `core:ui`, `feature:*` `src/test` | Roborazzi + Robolectric | yes |
| UI behavior | `feature:*/src/test` | Compose UI test on Robolectric | yes |
| DAO | `core:data/src/test` | Room in-memory on Robolectric | yes |
| Benchmark | `benchmark/` | Macrobenchmark | no (`bench.sh`) |
| Instrumented on device | — | — | none in v1 |

Every test runs on the JVM. No emulator in the loop.

## 4. Golden image tests (render correctness)

Location: `core/imaging/src/test/resources/golden/<op>_<value>.png`
Fixture input: `fixtures/photo_512.png` (512×384, contains skin tone, sky, deep shadow, saturated red, neutral gray patch).

Rule set:
- Compare per channel; a pixel passes if `|actual − expected| ≤ 2` on every channel; the image passes if ≥ 99.9% of pixels pass.
- Tolerance and threshold are constants in `GoldenAssert.kt`. **Changing them is forbidden** (CLAUDE.md hard limit).
- A golden is created only by the task whose `done when` names it, by running the op on the fixture once and committing the output **after visually checking it in the PR**. The agent commits the golden; the human reviews the image on the branch the next morning.
- Golden file names are listed in the task. Any golden not listed is a check failure (`GoldenInventoryTest` compares directory contents to `golden_manifest.txt`).

## 5. Screenshot tests (UI)

- Roborazzi with `RobolectricDeviceQualifiers.Pixel6a`, `@GraphicsMode(NATIVE)`, portrait, font scale 1.0, both `AppTheme` modes where the component supports both.
- Compare with `changeThreshold = 0.01` (1% of pixels). Set once in `build.gradle.kts`; not overridable per test.
- Record only via `recordRoborazziDebug`, and only for goldens named in the current task. `verifyRoborazziDebug` is what `check` runs; a missing golden is a failure, not an auto-record.
- Text in screenshots uses the bundled Pretendard font so results are deterministic across machines.
- Naming: `<screen_or_component>_<state>.png` exactly as listed in tasks.md.

## 6. UI behavior tests

- Drive through `EditorIntent`s and assert on `EditorUiState`, then a thin Compose test that the state renders (one per screen). Do not test business logic through Compose.
- Use `FakeRenderer` (returns a solid-color bitmap instantly) and `FakeProjectRepository` from `core:imaging/src/testFixtures` and `core:data/src/testFixtures`.
- Gesture tests use `performTouchInput { pinch / swipe / doubleClick }`; assert on viewport values, not on pixels.

## 7. Fixtures (committed by a human in T03)

```
fixtures/
  photo_512.png          golden-test input (see §4)
  photo_12mp.jpg         4000×3000, EXIF orientation 6, ~3MB
  huge_6000x4000.jpg     downsample test (T04)
  transparent_256.png    checkerboard rendering test
  corrupt.jpg            32 random bytes, must fail with Unsupported
```
Fixtures are read-only. Tests copy them to a temp dir when they need a file URI.

## 8. Rules the agent must follow

- Write the test named in the task **before** the implementation; watch it fail; then implement. (test-driven-development)
- Never delete, `@Ignore`, `@Disabled`, or loosen an existing test to get green.
- Never add `Thread.sleep`. Use `runTest` and `advanceUntilIdle`.
- Never catch and swallow exceptions inside a test to make it pass.
- A flaky test is a bug: fix the root cause or block the task; do not add retries.
- Test names describe behavior: `undo after coalesced drag restores value before drag started`.

## 9. What is deliberately not tested in v1
- Real device performance (bench.sh, human-run).
- Accessibility (TalkBack) — deferred with tablet layout.
- Localization — strings are Korean only.
