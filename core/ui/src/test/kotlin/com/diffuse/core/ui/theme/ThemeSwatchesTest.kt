package com.diffuse.core.ui.theme

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/testing.md §5: Pixel 6a, NATIVE graphics, portrait, font scale 1.0. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class ThemeSwatchesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun themeSwatches() {
        compose.setContent { ThemeSwatches() }
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath("theme_swatches"),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
