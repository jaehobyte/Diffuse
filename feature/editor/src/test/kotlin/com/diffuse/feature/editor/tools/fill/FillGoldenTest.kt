package com.diffuse.feature.editor.tools.fill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.components.PromptBar
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

/** specs/generative_fill.md §9: the two goldens. The prompt-bar goldens are core:ui's. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class FillGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fillSheetOpen() {
        show(FillState())

        capture("fill_sheet_open")
    }

    /** 적용 carries the sheet's one accent only once there is something to apply. */
    @Test
    fun fillSheetTyped() {
        show(FillState(prompt = PROMPT))

        capture("fill_sheet_typed")
    }

    private fun show(state: FillState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        FillSheet(
                            state = state,
                            onCancel = {},
                            onApply = {},
                            promptBar = {
                                PromptBar(
                                    value = state.prompt,
                                    onValueChange = {},
                                    onSubmit = {},
                                    placeholder = PLACEHOLDER,
                                    onMicClick = {},
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    private companion object {
        const val PROMPT = "빨간 우산"
        const val PLACEHOLDER = "무엇으로 채울까요? 예: 빨간 우산"
    }
}
