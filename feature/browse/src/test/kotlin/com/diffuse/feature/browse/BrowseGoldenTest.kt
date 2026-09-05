package com.diffuse.feature.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.ui.ScreenshotOptions
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class BrowseGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showBrowse(projects: List<ProjectSummary>) {
        compose.setContent {
            BrowseScreen(
                projects = projects,
                nowMillis = NOW,
                onOpen = {},
                onDuplicate = {},
                onDelete = {},
                onNewProject = {},
                thumbnail = { fakeThumbnail(it) },
            )
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    @Test
    fun browseGrid() {
        showBrowse(sampleProjects)

        capture("browse_grid")
    }

    @Test
    fun browseEmpty() {
        showBrowse(emptyList())

        capture("browse_empty")
    }

    @Test
    fun browseTileActions() {
        showBrowse(sampleProjects)

        compose.onNodeWithTag(tileTag("2")).performTouchInput { longClick() }
        compose.waitForIdle()

        capture("browse_tile_actions")
    }
}
