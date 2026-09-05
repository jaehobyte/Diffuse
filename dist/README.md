# dist

Build artifacts committed for convenience. Regenerate with:

    ./gradlew assembleDebug

`diffuse-0.1.0-debug.apk` is the v0.1.0 debug build (signed with the local debug key, so it
is installable). It launches into Browse: import from the Photo Picker, edit on the canvas
(light / color / crop / detail), and export to MediaStore.

The v0.2.0 APK is **not** committed here — it is attached to the
[v0.2.0 release](https://github.com/jaehobyte/Diffuse/releases/tag/v0.2.0) instead, to keep
a ~49MB binary out of the repository.

Sizes, against the APK budget in specs/architecture.md §8 (`< 500MB`, raised from 15MB by
ADR-008 when models were still bundled):

| Build | Size |
|---|---|
| `diffuse-0.1.0-debug.apk`, as committed | 23.16 MB |
| debug at v0.2.0 (unminified, debug metadata) | 48.75 MB |
| release at v0.2.0 (unsigned, `isMinifyEnabled = false`) | 16.21 MB |

R8 is still off, so the release figure is the unshrunk size. The debug build has roughly
doubled since v0.1.0; that growth is unexplained and worth a look before it matters.

Neither size includes `app/src/main/assets/models/`. Those EdgeTAM weights are untracked
leftovers from the segmentation line that was reverted before v0.2.0, and no code on `main`
reads them, so the measurements above were taken with that directory moved aside.
