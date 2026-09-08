package com.diffuse.feature.editor.tools.auto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.AutoStyle
import com.diffuse.core.ai.FakeAutoEnhanceProvider
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

/**
 * specs/auto_enhance.md §8's two goldens, both of a **fake** plan: the golden asserts the
 * rendering, never the model's taste.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class AutoGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    /** The moment the call returns: three chips, the slider at full, and no reason yet to show. */
    @Test
    fun autoSheetOpen() {
        capture("auto_sheet_open", AutoState())
    }

    /** …and the same sheet holding a plan, which is what the user actually judges. */
    @Test
    fun autoSheetResult() {
        capture(
            "auto_sheet_result",
            AutoState(
                style = AutoStyle.Balanced,
                plan = FakeAutoEnhanceProvider.PLANS.getValue(AutoStyle.Balanced),
                reason = FakeAutoEnhanceProvider.REASON,
                intensity = INTENSITY,
            ),
        )
    }

    private fun capture(name: String, state: AutoState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    AutoSheet(
                        state = state,
                        onStyleChange = {},
                        onIntensityChange = {},
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
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

    private companion object {
        const val INTENSITY = 80
    }
}
