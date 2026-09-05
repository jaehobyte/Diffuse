package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
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
class EditSheetGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showSheet(rows: Int) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Tokens.editBackground),
                ) {
                    EditSheet(
                        title = "라이트",
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        repeat(rows) { PlaceholderControl(index = it) }
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

    /** Stands in for the sliders T12 adds; the sheet frame is what is under test. */
    @Composable
    private fun PlaceholderControl(index: Int) {
        val colors = LocalAppColors.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "컨트롤 ${index + 1}", style = Typography.label, color = colors.inkSecondary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceRaised),
            )
        }
    }

    @Test
    fun sheetCollapsed() {
        // Two controls: the sheet wraps its content, well under the 45% cap.
        showSheet(rows = 2)

        capture("sheet_collapsed")
    }

    @Test
    fun sheetExpanded() {
        // More controls than fit: the sheet stops at 45% and the content scrolls.
        showSheet(rows = 12)

        capture("sheet_expanded")
    }
}
