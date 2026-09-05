# dist

Build artifacts committed for convenience. Regenerate with:

    ./gradlew assembleDebug

`diffuse-0.1.0-debug.apk` is the debug build (signed with the local debug key, so it is
installable). It launches to an empty Compose screen: the editor screens exist as
components but are not reachable until T21 wires the navigation graph.

Sizes at the time of writing, against the 15MB budget in specs/architecture.md §8:

| Build | Size |
|---|---|
| debug (unminified, debug metadata) | 23.13 MB |
| release (unsigned, `isMinifyEnabled = false`) | 16.06 MB |

The release build is over budget and R8 is not enabled yet.
