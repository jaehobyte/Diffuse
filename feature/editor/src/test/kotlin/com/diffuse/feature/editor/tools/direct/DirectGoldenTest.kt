package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.EditPlan
import com.diffuse.core.ai.PlanStep
import com.diffuse.core.imaging.model.AdjustKind
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

/** specs/vibe_edit.md §12: the two goldens. No render golden — this feature adds no renderer path. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class DirectGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directSheetOpen() {
        show(DirectState())

        capture("direct_sheet_open")
    }

    @Test
    fun directPlanPreview() {
        show(
            DirectState(
                request = REQUEST,
                plan = EditPlan(
                    listOf(
                        PlanStep.Select("나무"),
                        PlanStep.Adjust(AdjustKind.Saturation, 0.3f, masked = true),
                    ),
                ),
            ),
        )

        capture("direct_plan_preview")
    }

    private fun show(state: DirectState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        DirectSheet(
                            state = state,
                            onCancel = {},
                            onApply = {},
                            promptBar = {
                                PromptBar(
                                    value = state.request,
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
        const val REQUEST = "나무를 좀 더 푸르게 해줘"
        const val PLACEHOLDER = "무엇을 바꿀까요? 예: 나무를 더 푸르게"
    }
}
