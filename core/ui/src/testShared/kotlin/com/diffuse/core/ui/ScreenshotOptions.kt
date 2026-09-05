package com.diffuse.core.ui

import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * specs/testing.md §5: the compare threshold is set once in build.gradle.kts and is
 * not overridable per test. Roborazzi's Gradle extension exposes no threshold, so the
 * value is passed in as a system property and this is its only reader.
 */
object ScreenshotOptions {

    private const val THRESHOLD_PROPERTY = "diffuse.roborazzi.changeThreshold"

    val options: RoborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            changeThreshold = requireNotNull(System.getProperty(THRESHOLD_PROPERTY)) {
                "$THRESHOLD_PROPERTY is unset; specs/testing.md §5 requires it in build.gradle.kts"
            }.toFloat(),
        ),
    )

    /**
     * Goldens are committed, so they must not land in `build/`. The Roborazzi Gradle
     * plugin owns `roborazzi.output.dir`, so the path is fixed here instead; relative
     * capture paths resolve against the module directory.
     */
    fun goldenPath(name: String): String = "src/test/screenshots/$name.png"
}
