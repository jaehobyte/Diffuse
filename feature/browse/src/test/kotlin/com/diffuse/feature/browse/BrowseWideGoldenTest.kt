package com.diffuse.feature.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/browse.md: 3 columns from 600dp. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h1280dp-xhdpi")
class BrowseWideGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun browseGridWide() {
        compose.setContent {
            BrowseScreen(
                projects = sampleProjects,
                nowMillis = NOW,
                onOpen = {},
                onDuplicate = {},
                onDelete = {},
                onNewProject = {},
                thumbnail = { fakeThumbnail(it) },
            )
        }
        compose.waitForIdle()

        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath("browse_grid_wide"),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
