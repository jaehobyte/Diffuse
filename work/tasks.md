# Task

## Goal

Detect whether the loaded editor image is a portrait on device and use that result to show a portrait-prioritized tool menu.

## Background

The user wants a different menu when the input photo is a portrait, because portrait edits usually start with retouching tasks such as blemish correction and wrinkle reduction.

Use ML Kit Face Detection through Google Play services as the first implementation. This is still on-device inference, avoids a new bundled model asset, and fits the existing APK-size decision better than adding the bundled `com.google.mlkit:face-detection` artifact. Configure install-time model download with `com.google.mlkit.vision.DEPENDENCIES=face`, and treat model-not-ready or detector failure as "unknown", not as a blocking editor error.

The relevant model survey:

- ML Kit Face Detection: stable on-device face detector; Google documents FAST mode, no landmarks/contours by default, bitmap input, and Play-services delivery with about 800 KB app-size increase. Bundled delivery is immediate but adds about 6.9 MB.
- MediaPipe Face Detector / BlazeFace: viable, but requires carrying task/model assets and another runtime surface for a simple classification gate.
- ML Kit Selfie Segmenter and MediaPipe Image Segmenter: useful when a person mask is needed, but segmentation is heavier and ML Kit Selfie Segmenter is beta. MediaPipe SelfieSegmenter is about 33 ms on Pixel 6; SelfieMulticlass is much heavier. This task needs only a menu decision, not a mask.
- EfficientDet/COCO person detection: detects full bodies, but the requested retouch menu is face-driven. A small back-facing person should not trigger blemish/wrinkle-first UI unless a usable face is visible.

Current editor menu architecture:

- `Tool.kt` owns `ToolGroup`, `Tool`, `StripItem`, and `stripItems(level)`.
- `EditorRoute` owns `toolLevel` as `rememberSaveable` UI state and resets it to `Root` when sheets close.
- `EditorViewModel` owns loaded document/source state, preview rendering, and injected AI providers through `EditorAi`.
- `core:ai` is an Android library and already owns AI provider interfaces, implementations, Hilt bindings, and test fakes.

## Scope

### Modify

- `gradle/libs.versions.toml`
- `core/ai/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `core/ai/src/main/kotlin/com/diffuse/core/ai/`
- `core/ai/src/testShared/kotlin/com/diffuse/core/ai/`
- `core/ai/src/test/kotlin/com/diffuse/core/ai/`
- `core/ai/src/main/kotlin/com/diffuse/core/ai/AiModule.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorAi.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorViewModel.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorRoute.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorScreen.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/Tool.kt`
- `feature/editor/src/main/kotlin/com/diffuse/feature/editor/EditorToolStrip.kt`
- related editor/core AI tests and goldens only if their visible menu ordering changes

### Do not modify

- Do not add new retouch operations, generative workflows, or nonfunctional "blemish"/"wrinkle" tools in this task.
- Do not change document persistence or serialize portrait detection state into `EditDocument`.
- Do not replace the existing SAM 3 selection provider or generative providers.
- Do not add MediaPipe, TensorFlow Lite model assets, NNAPI-specific code, or a new `core:*` module.
- Do not make network calls for portrait detection.

## Requirements

1. Add a fake-able `PortraitDetector` boundary in `core:ai`.
2. Implement the production detector with ML Kit Play-services face detection:
   - dependency `com.google.android.gms:play-services-mlkit-face-detection:17.1.0`;
   - `FaceDetectorOptions.PERFORMANCE_MODE_FAST`;
   - no landmarks, contours, classifications, or tracking;
   - bitmap input through `InputImage.fromBitmap(bitmap, 0)`;
   - detection runs off the main thread from the caller's coroutine context.
3. Define a small result model that can represent at least `Portrait`, `NotPortrait`, and `Unknown`.
4. Classify as `Portrait` only when at least one detected face is large enough to justify portrait-retouch UI. Use a deterministic threshold based on face bounds relative to image size; start with a minimum face box of 10% of image width or 100 px on the detector input, whichever is practical after preview scaling.
5. Run portrait detection once after the source image is rendered/available for a loaded project. Use the existing preview/source bitmap rather than decoding the original again.
6. Detection must be lifecycle-safe and cancellation-aware:
   - cancel stale detection work when a new document/source is loaded;
   - ignore results that do not correspond to the current document/source;
   - never block canvas preview rendering.
7. Store portrait mode as editor UI state only. It resets naturally when a different project enters the editor.
8. Add a menu profile separate from `ToolGroup`, for example `ToolMenuProfile.General | Portrait`.
9. Keep the existing general menu unchanged:
   - Root: `라이트`, `색상`, `혼합`, `자르기`, `디테일`, `AI`
   - AI: `뒤로`, `선택`, `지우기`, `채우기`, `확대`, `스타일`, `자동`, `지시`
10. In portrait mode, prioritize the existing tools that are most relevant to portrait editing without adding new behavior:
   - Root: `자동`, `디테일`, `라이트`, `색상`, `혼합`, `자르기`, `AI`
   - AI: `뒤로`, `선택`, `지우기`, `채우기`, `확대`, `스타일`, `지시`
   - `자동` moves to the portrait root and must not appear twice in the active menu profile.
11. `Unknown` and `NotPortrait` both use the general menu. The editor should not show an error snackbar for portrait detector failure.
12. Changing menu profile must not close an already open sheet or change the selected tool. If detection finishes while a sheet is open, the new menu ordering applies after the sheet closes or when the strip is visible.
13. Existing disabled-tool behavior remains unchanged: disabled tools stay tappable so they can explain themselves.
14. Add Korean user-facing strings only if new visible labels are introduced. This task should not need a visible "portrait mode" label.

## Acceptance Criteria

- [ ] A portrait image causes the editor root strip to show `자동` and `디테일` before the general adjustment tools.
- [ ] A non-portrait image keeps the existing general root and AI menus.
- [ ] Detector unavailable, model not downloaded yet, or detector failure falls back to the general menu without snackbar noise.
- [ ] `자동` appears exactly once in each active menu profile.
- [ ] No portrait detection result is written to `EditDocument` or persisted project data.
- [ ] Existing sheet open/apply/cancel/back behavior still works with both menu profiles.
- [ ] New unit tests cover detector result mapping and threshold behavior using fakes where ML Kit cannot run under JVM tests.
- [ ] New editor UI tests cover general vs portrait menu ordering and AI level contents.
- [ ] Existing related tests pass.
- [ ] No unrelated files or behavior are changed.

## Validation

Run focused checks during development:

```bash
./gradlew :core:ai:testDebugUnitTest :feature:editor:testDebugUnitTest
```

Before declaring the task complete, run repository validation when practical:

```bash
scripts/check.sh
```

## References

- `specs/architecture.md` §2, §4, §5
- `specs/editor_shell.md`
- `specs/tool_groups.md`
- `DESIGN.md` §1, §4, §5, §7
- `work/decisions.md` T79
- ML Kit Face Detection Android docs: https://developers.google.com/ml-kit/vision/face-detection/android
- ML Kit Selfie Segmentation Android docs: https://developers.google.com/ml-kit/vision/selfie-segmentation/android
- MediaPipe Image Segmenter docs: https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter
- Android NNAPI migration guide: https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
- Qualcomm S26/Snapdragon 8 Elite Gen 5 for Galaxy note: https://www.qualcomm.com/news/releases/2026/02/qualcomm-unveils-the-snapdragon-8-elite-gen-5-for-galaxy--drivin

## Notes

S26 Ultra is expected to have more than enough CPU/GPU/NPU headroom for this gate. Do not optimize for the NPU directly in this task: Android's NNAPI path is deprecated, and the recommended Android production path is Play-services LiteRT/TFLite plus optional GPU for custom models. ML Kit owns its own acceleration details.

The portrait detector is a menu personalization signal, not an image-editing primitive. If later tasks add actual blemish or wrinkle tools, they should define their own operation/provider contracts and may reuse this portrait state only as a default menu hint.
