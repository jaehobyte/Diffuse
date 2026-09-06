package com.diffuse.feature.editor.tools.select

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.canvas.CanvasViewport
import com.diffuse.feature.editor.canvas.EditorCanvas
import com.diffuse.feature.editor.canvas.selectionOverlaySlot
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/selection_tool.md §10, the two screenshot goldens T30 names. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class SelectGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectSheetOpen() {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        SelectSheet(
                            state = SelectionState(
                                mask = circleMask(),
                                points = listOf(PointF(0.5f, 0.5f)),
                                labels = listOf(true),
                            ),
                            onInvert = {},
                            onClear = {},
                            onCancel = {},
                            onApply = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        capture("select_sheet_open")
    }

    @Test
    fun selectMaskPreview() {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                var viewport by remember { mutableStateOf(CanvasViewport()) }
                EditorCanvas(
                    bitmap = testImage(),
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    overlay = selectionOverlaySlot(
                        mask = circleMask(),
                        points = listOf(PointF(0.5f, 0.5f), PointF(0.2f, 0.25f)),
                        labels = listOf(true, false),
                    ),
                )
            }
        }
        compose.waitForIdle()

        capture("select_mask_preview")
    }

    /** Matches the preview bitmap's 400×300, so the overlay lands exactly on the photo. */
    private fun circleMask(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ALPHA_8)
        val radius = HEIGHT * 0.3f
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val dx = x - WIDTH / 2f
                val dy = y - HEIGHT / 2f
                val inside = dx * dx + dy * dy <= radius * radius
                bitmap.setPixel(x, y, if (inside) MaskOps.OPAQUE shl 24 else 0)
            }
        }
        return bitmap
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
    }
}
