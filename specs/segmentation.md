# specs/segmentation.md — EdgeTAM on-device segmentation

Owner tasks: T27 (runtime), T28 (delivery)
Module: `core/ai/edgetam`
Decisions: ADR-007 (EdgeTAM via ExecuTorch), ADR-008 (bundle in APK)

## 1. Model
- EdgeTAM (Meta, CVPR 2025), Apache-2.0. ExecuTorch XNNPACK export, two files:
  - `edgetam_encoder_xnnpack_fp32.pte` — 19.7MB. Input `image (1,3,1024,1024) fp32` → `image_embed (1,256,64,64)`, `feat_s0 (1,32,256,256)`, `feat_s1 (1,64,128,128)`
  - `edgetam_decoder_xnnpack_fp16.pte` — 12.6MB. Input the three tensors above + `points (1,1,N,2) fp32` pixel coords in 1024-space + `labels (1,1,N) int64` (1 = fg, 0 = bg) → `mask_logits (1,1,3,256,256)`, `iou (1,1,3)`. Takes and returns fp32 tensors.
- Source: `mlboydaisuke/EdgeTAM-ExecuTorch` on Hugging Face. Record the SHA-256 of both files in `core/ai/edgetam/ModelManifest.kt` and verify on first load; mismatch → `Unavailable(Io)`.
- Runtime: `org.pytorch:executorch-android` (version pinned in `libs.versions.toml`). XNNPACK CPU backend only in v2; no NNAPI/GPU delegate.

## 2. Delivery (ADR-008: bundle)
- Both files live in `app/src/main/assets/models/`. APK budget in architecture.md §8 rises from 15MB to **50MB**; update the `check.sh` size assertion.
- On first `prepare`, copy assets to `filesDir/models/` (ExecuTorch loads from a file path), verify SHA-256, then load. Subsequent launches skip the copy if the hash file matches.
- `availability` is `Unavailable(Unsupported)` on non-arm64/x86_64 ABIs (`Build.SUPPORTED_64_BIT_ABIS` empty) and `Unavailable(Io)` if the copy or hash fails. The selection tool shows a greyed icon and a snackbar with the reason on tap.
- Test fixture copies of the same files live in `fixtures/models/` via git-lfs (not in the APK).

## 3. `prepare(image)`
1. Letterbox the ARGB bitmap into 1024×1024: scale so the long edge = 1024, pad the short edge with black, remember `(scale, padX, padY)`.
2. Convert to fp32 CHW, RGB / 255, then normalize with ImageNet mean `(0.485, 0.456, 0.406)` and std `(0.229, 0.224, 0.225)`.
3. Run the encoder module. Keep the three output tensors in `ImageEmbedding.payload` together with the letterbox params.
4. Memory: the three tensors ≈ 4MB + 8MB + 4MB fp32; plus the input tensor 12MB during the call. Release the input tensor before returning. Only one embedding alive per provider instance.
5. Target latency: < 700ms on a Pixel 6a. Not asserted in `check`; measured in `bench.sh`.

## 4. `segment(embedding, prompt)`
1. Map each normalized point to 1024-space: `x1024 = x × imageWidth × scale + padX`, same for y.
2. Build `points (1,1,N,2)` and `labels (1,1,N)`.
3. Run the decoder. Pick index `argmax(iou)` from the three masks.
4. `sigmoid(logit) > 0.5` → binary, at 256×256 in letterbox space.
5. Crop the letterbox padding, bilinear-upsample to `imageWidth × imageHeight`, write to an `ALPHA_8` bitmap. Feather: none in v2 (hard edge); a 1px blur is D-level.
6. Return `SegMask(alpha, iou[best])`.
Target latency: < 60ms.

## 5. Threading & cancellation
- `Dispatchers.default`. Encoder and decoder calls are blocking JNI; check `ensureActive()` before each and release outputs if cancelled after.
- `EdgeTamProvider` is a `@Singleton`; module loading is guarded by a `Mutex`.

## 6. Failure modes
| Condition | Result |
|---|---|
| `.pte` missing / hash mismatch | `Unavailable(Io)` |
| unsupported ABI | `Unavailable(Unsupported)` |
| `OutOfMemoryError` in `prepare` | `Failure(TooLarge)`; provider stays `Ready` |
| decoder throws | `Failure(Unsupported)` with the message logged |
| all three IoU scores < 0.3 | still return the best mask; UI may show a "낮은 신뢰도" hint (selection_tool.md) |

## 7. Tests
- `EdgeTamProviderTest` (Robolectric, loads `fixtures/models/`):
  - `prepare` on `photo_512.png` succeeds; embedding dims match the contract.
  - a fg click at the center of the red patch → mask bbox covers ≥ 80% of the patch, ≤ 5% of the gray patch.
  - fg click + bg click inside the patch → mask area shrinks.
  - hash mismatch (corrupt a byte in a temp copy) → `Unavailable(Io)`.
- `LetterboxMathTest` (pure JVM): round-trip of point mapping for landscape and portrait sizes.
- `bench.sh`: encoder and decoder wall time on the 12MP fixture.
- If the ExecuTorch native library cannot load under Robolectric on the loop machine, `EdgeTamProviderTest` is tagged `@Tag("device")` and excluded from `check.sh`; `LetterboxMathTest` and all fake-based tests remain. Record this in `blocked.md` so a human runs it on a device.
