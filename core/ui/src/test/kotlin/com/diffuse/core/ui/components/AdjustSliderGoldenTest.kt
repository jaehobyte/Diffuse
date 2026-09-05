package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Typography
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
class AdjustSliderGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showSlider(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        zeroCentered: Boolean,
    ) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                val colors = LocalAppColors.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = label, style = Typography.label, color = colors.inkSecondary)
                    AdjustSlider(
                        value = value,
                        range = range,
                        zeroCentered = zeroCentered,
                        onChange = {},
                        onChangeFinished = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onNodeWithTag(AdjustSliderTestTag).captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    @Test
    fun sliderDefault() {
        // A one-sided adjustment (Sharpen): no centre tick.
        showSlider(label = "선명하게", value = 0.35f, range = 0f..1f, zeroCentered = false)

        capture("slider_default")
    }

    @Test
    fun sliderZeroCentered() {
        // A zero-centred adjustment (Exposure): 2dp tick at the track centre.
        showSlider(label = "노출", value = -0.4f, range = -1f..1f, zeroCentered = true)

        capture("slider_zero_centered")
    }
}
