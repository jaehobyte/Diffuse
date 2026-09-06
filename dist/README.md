# dist

Build artifacts committed for convenience. Regenerate with:

    ./gradlew assembleDebug

`diffuse-0.1.0-debug.apk` is the v0.1.0 debug build (signed with the local debug key, so it
is installable). It launches into Browse: import from the Photo Picker, edit on the canvas
(light / color / crop / detail), and export to MediaStore.

Later APKs are **not** committed here — they are attached to their releases instead, to keep
a ~24MB binary out of the repository:

- [v0.2.0](https://github.com/jaehobyte/Diffuse/releases/tag/v0.2.0)
- [v0.3.0](https://github.com/jaehobyte/Diffuse/releases/tag/v0.3.0) — server-side SAM 3
  selection, prompts, generative erase
- [v0.3.1](https://github.com/jaehobyte/Diffuse/releases/tag/v0.3.1) — session-lifecycle fixes
  found on the first device run
- [v0.3.6](https://github.com/jaehobyte/Diffuse/releases/tag/v0.3.6) — the first build verified on
  a phone against the real model. Ships **no** server address and **no** token: a published APK
  must not carry either, and an empty address is what makes the app open its 서버 설정 sheet on
  the first tap of 선택.
- [v0.4.0](https://github.com/jaehobyte/Diffuse/releases/tag/v0.4.0) — the generative eraser calls
  `gemini-2.5-flash-image` from the device (ADR-011) instead of proxying through `~/sam3-server`.
  Ships **no** Gemini key either: the 서버 설정 sheet now has three fields, and 지우기 opens it
  when the key is blank.
- [v0.5.0](https://github.com/jaehobyte/Diffuse/releases/tag/v0.5.0) — the 지시 tool: one sentence
  becomes a workflow. `gemini-2.5-flash` is given the photo and the four things this editor can do
  and answers with function calls, which the app previews as a Korean step list and runs against
  the providers the manual tools already use (ADR-012). Also the three defects the v0.4.0 device
  run found: a masked adjustment after a generative erase was being overwritten by it, the erase
  mask had no margin so the object left a halo, and the eraser could answer with the whitened
  input unchanged. Same credential story as v0.4.0 — no key, no server address.

v0.3.0 and v0.3.1 default to `http://10.0.2.2:8080`, the emulator's alias for its host, so the
선택 tool cannot work on a physical device. Use v0.3.6.

Sizes, against the APK budget in specs/architecture.md §8 (**< 15MB**; ADR-008 raised it to
50MB while EdgeTAM weights were bundled, and was struck with ADR-009 when they were dropped):

| Build | Size |
|---|---|
| `diffuse-0.1.0-debug.apk`, as committed | 23.16 MB |
| debug at v0.2.0 (unminified, debug metadata) | 48.75 MB |
| release at v0.2.0 (unsigned, `isMinifyEnabled = false`) | 16.21 MB |
| debug at v0.3.0 | 23.96 MB |
| debug at v0.3.1 | 23.96 MB |
| debug at v0.3.6 | 23.96 MB |
| debug at v0.4.0 | 23.99 MB |
| debug at v0.5.0 | 24.07 MB |
| release at v0.3.0 (unsigned, `isMinifyEnabled = false`) | 17.40 MB |
| release at v0.4.0 (unsigned, `isMinifyEnabled = false`) | 17.42 MB |
| release at v0.5.0 (unsigned, `isMinifyEnabled = false`) | 17.46 MB |

**The release build is 2.5 MB over budget.** R8 is still off, so that is the unshrunk size:
material-icons-extended and unused Compose ship whole, and the Pretendard variable font is
2.81 MB of it. Turning `isMinifyEnabled` on is the obvious first move and has not been tried.

The debug build is back at v0.1.0's size. The doubling noted at v0.2.0 is gone and nothing in
the v2 work explains either direction, so treat both figures as measurements rather than as a
trend — the v0.2.0 number is the one worth re-taking on a clean build.

v0.3.0 no longer bundles any model weights: segmentation is a call to the SAM 3 service
(ADR-009), so `app/src/main/assets/models/` was deleted rather than measured around.
