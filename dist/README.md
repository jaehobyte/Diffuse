# dist

Build artifacts committed for convenience. Regenerate with:

    ./gradlew assembleDebug

`diffuse-0.1.0-debug.apk` is the debug build (signed with the local debug key, so it is
installable). It launches into Browse: import from the Photo Picker, edit on the canvas
(light / color / crop / detail), and export to MediaStore.

Sizes at the time of writing, against the 15MB budget in specs/architecture.md §8:

| Build | Size |
|---|---|
| debug (unminified, debug metadata) | 23.16 MB |
| release (unsigned, `isMinifyEnabled = false`) | 16.19 MB |

The release build is over budget and R8 is not enabled yet.
