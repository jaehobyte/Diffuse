package com.diffuse.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.tools.color.ColorSheet
import com.diffuse.feature.editor.tools.detail.DetailSheet
import com.diffuse.feature.editor.tools.light.LightSheet
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
class ToolSheetGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
        .withAdjust(AdjustKind.Exposure, 0.35f)
        .withAdjust(AdjustKind.Shadows, -0.2f)
        .withAdjust(AdjustKind.Saturation, 0.3f)
        .withAdjust(AdjustKind.Sharpen, 0.45f)

    private fun showSheet(content: @Composable (EditDocument) -> Unit) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) { content(document) }
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

    @Test
    fun lightSheetOpen() {
        showSheet { document ->
            LightSheet(
                document = document,
                onValueChange = { _, _ -> },
                onValueChangeFinished = {},
                onCancel = {},
                onApply = {},
            )
        }

        capture("light_sheet_open")
    }

    @Test
    fun colorSheetOpen() {
        showSheet { document ->
            ColorSheet(
                document = document,
                onValueChange = { _, _ -> },
                onValueChangeFinished = {},
                onCancel = {},
                onApply = {},
            )
        }

        capture("color_sheet_open")
    }

    @Test
    fun detailSheetOpen() {
        showSheet { document ->
            DetailSheet(
                document = document,
                onValueChange = { _, _ -> },
                onValueChangeFinished = {},
                onCancel = {},
                onApply = {},
            )
        }

        capture("detail_sheet_open")
    }
}
