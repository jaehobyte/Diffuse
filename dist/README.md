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

Sizes, against the APK budget in specs/architecture.md §8 (**< 15MB**; ADR-008 raised it to
50MB while EdgeTAM weights were bundled, and was struck with ADR-009 when they were dropped):

| Build | Size |
|---|---|
| `diffuse-0.1.0-debug.apk`, as committed | 23.16 MB |
| debug at v0.2.0 (unminified, debug metadata) | 48.75 MB |
| release at v0.2.0 (unsigned, `isMinifyEnabled = false`) | 16.21 MB |
| debug at v0.3.0 | 23.96 MB |
| debug at v0.3.1 | 23.96 MB |
| release at v0.3.0 (unsigned, `isMinifyEnabled = false`) | 17.40 MB |

**The release build is 2.4 MB over budget.** R8 is still off, so that is the unshrunk size:
material-icons-extended and unused Compose ship whole, and the Pretendard variable font is
2.81 MB of it. Turning `isMinifyEnabled` on is the obvious first move and has not been tried.

The debug build is back at v0.1.0's size. The doubling noted at v0.2.0 is gone and nothing in
the v2 work explains either direction, so treat both figures as measurements rather than as a
trend — the v0.2.0 number is the one worth re-taking on a clean build.

v0.3.0 no longer bundles any model weights: segmentation is a call to the SAM 3 service
(ADR-009), so `app/src/main/assets/models/` was deleted rather than measured around.
