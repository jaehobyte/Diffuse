package com.diffuse.feature.editor.tools.style

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.style.StyleCatalog
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
 * specs/style_match.md §9's two goldens. No tiles are rendered here: §7 says an unfinished tile is
 * flat `surfaceCard`, and that flat state is exactly what a golden can assert without depending on
 * a photograph.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class StyleGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    private val presets = StyleCatalog.load(
        ApplicationProvider.getApplicationContext<Context>().assets,
    )

    /** 원본 selected, twelve tiles waiting, and 적용 disabled because nothing is chosen. */
    @Test
    fun styleSheetOpen() {
        capture("style_sheet_open", StyleState(presets = presets))
    }

    /**
     * …and a style picked: the ring moves, 세부 appears, and 적용 lights up. 내추럴 rather than a
     * more characterful preset because it is the third tile and so still on screen — a ring the
     * golden cannot see is not asserting the selected state.
     */
    @Test
    fun styleSheetSelected() {
        capture(
            "style_sheet_selected",
            StyleState(presets = presets, selected = "natural-enhance", intensity = INTENSITY),
        )
    }

    private fun capture(name: String, state: StyleState) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        StyleSheet(
                            state = state,
                            onSelect = {},
                            onVariantSelect = {},
                            onIntensityChange = {},
                            onCancel = {},
                            onApply = {},
                        )
                    }
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
        const val INTENSITY = 70
    }
}
