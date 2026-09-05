package com.diffuse.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.tools.light.LightKinds
import com.diffuse.feature.editor.tools.light.LightSheet
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/adjust_light.md §Behavior. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class LightSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val base = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
    private lateinit var history: HistoryStack

    private fun showSheet(initial: EditDocument = base) {
        history = HistoryStack(initial)
        compose.setContent {
            val document by history.current.collectAsState()
            AppTheme(ThemeMode.Edit) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Tokens.editBackground),
                ) {
                    LightSheet(
                        document = document,
                        onValueChange = { kind, value ->
                            history.push(
                                document.withAdjust(kind, value),
                                coalesceKey = "adjust:$kind",
                            )
                        },
                        onValueChangeFinished = { history.commitCoalesce() },
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the sheet shows every light kind in order`() {
        showSheet()

        assertEquals(
            listOf(
                AdjustKind.Exposure,
                AdjustKind.Contrast,
                AdjustKind.Highlights,
                AdjustKind.Shadows,
            ),
            LightKinds,
        )
        LightKinds.forEach { compose.onNodeWithTag(adjustSliderTag(it)).assertExists() }
    }

    @Test
    fun `a drag collapses into one history entry once released`() {
        showSheet()

        compose.onNodeWithTag(adjustSliderTag(AdjustKind.Exposure))
            .performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertTrue("expected the drag to move exposure", history.current.value.adjustValue(AdjustKind.Exposure) != 0f)
        assertTrue(history.canUndo.value)

        // One entry: a single undo returns to the untouched document.
        history.undo()
        assertEquals(base, history.current.value)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun `opening the sheet shows the stored values, not zeros`() {
        showSheet(base.withAdjust(AdjustKind.Contrast, 0.42f))

        compose.onNodeWithText("+42").assertExists()
    }

    @Test
    fun `double tap resets a slider and removes the operation`() {
        showSheet(base.withAdjust(AdjustKind.Exposure, 0.6f))

        compose.onNodeWithTag(adjustSliderTag(AdjustKind.Exposure))
            .performTouchInput { doubleClick() }
        compose.waitForIdle()

        assertEquals(0f, history.current.value.adjustValue(AdjustKind.Exposure), 0f)
        assertTrue(
            "a neutral adjust must be removed, not stored",
            history.current.value.operations.isEmpty(),
        )
    }
}
