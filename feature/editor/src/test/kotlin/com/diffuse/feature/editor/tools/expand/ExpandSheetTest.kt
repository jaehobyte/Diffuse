package com.diffuse.feature.editor.tools.expand

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.Margins
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/outpaint.md §6, §8. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `적용 is disabled while every margin is zero`() {
        show(ExpandState())

        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    @Test
    fun `적용 is enabled once one edge has been dragged out`() {
        show(ExpandState(margins = Margins(top = 0.2f)))

        compose.onNodeWithText("적용").assertIsEnabled()
    }

    @Test
    fun `적용 is disabled while the call is in flight`() {
        show(ExpandState(margins = MARGINS, busy = true))

        compose.onNodeWithText("적용").assertIsNotEnabled()
    }

    /** §6: the readout names the ratio being left and the one being made. */
    @Test
    fun `the readout shows the source ratio arriving at the expanded one`() {
        show(ExpandState(margins = Margins(top = 0.5f, bottom = 0.5f)), sourceAspect = 4f / 3f)

        compose.onNodeWithText("비율 4:3 → 2:3").assertExists()
    }

    @Test
    fun `with no margins both sides of the readout are the source's own ratio`() {
        show(ExpandState(), sourceAspect = 4f / 3f)

        compose.onNodeWithText("비율 4:3 → 4:3").assertExists()
    }

    /** §6: 확대 continues a scene the model can already see, so there is nothing to type. */
    @Test
    fun `the sheet carries no prompt bar`() {
        show(ExpandState())

        compose.onNodeWithText("무엇으로 채울까요? 예: 빨간 우산").assertDoesNotExist()
    }

    private fun show(state: ExpandState, sourceAspect: Float = 1f) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                ExpandSheet(
                    state = state,
                    sourceAspect = sourceAspect,
                    onCancel = {},
                    onApply = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        val MARGINS = Margins(left = 0.25f, right = 0.25f)
    }
}
