package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/prompt_input.md §2: the three states the bar has. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class PromptBarGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun promptBarEmpty() = capture("prompt_bar_empty", value = "")

    @Test
    fun promptBarFilled() = capture("prompt_bar_filled", value = "노란 버스")

    @Test
    fun promptBarListening() =
        capture("prompt_bar_listening", value = "사", listening = true)

    private fun capture(name: String, value: String, listening: Boolean = false) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Tokens.editSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    PromptBar(
                        value = value,
                        onValueChange = {},
                        onSubmit = {},
                        onMicClick = {},
                        listening = listening,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
