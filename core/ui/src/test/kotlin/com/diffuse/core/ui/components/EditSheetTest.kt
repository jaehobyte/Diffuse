package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class EditSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private var cancels = 0
    private var applies = 0

    private fun showSheet(contentHeight: Int) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                // The sheet rises over the canvas, so measure it against a full screen.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Tokens.editBackground),
                ) {
                    EditSheet(
                        title = "배경 제거",
                        onCancel = { cancels++ },
                        onApply = { applies++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        Box(Modifier.height(contentHeight.dp)) { Text("내용") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `cancel and apply report separately`() {
        showSheet(contentHeight = 80)

        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("적용").performClick()
        compose.waitForIdle()

        assertEquals(1, cancels)
        assertEquals(1, applies)
    }

    @Test
    fun `the sheet never grows past 45 percent of the screen`() {
        // Content far taller than the cap; the canvas must stay at least half visible.
        showSheet(contentHeight = 2000)

        val screenHeight = compose.onRoot().fetchSemanticsNode().size.height
        val sheetHeight = compose.onNodeWithTag(EditSheetTestTag)
            .fetchSemanticsNode().size.height

        assertTrue(
            "sheet took $sheetHeight of $screenHeight",
            sheetHeight <= screenHeight * 0.45f + 1f,
        )
    }

    @Test
    fun `a short sheet wraps its content instead of filling the cap`() {
        showSheet(contentHeight = 60)

        val screenHeight = compose.onRoot().fetchSemanticsNode().size.height
        val sheetHeight = compose.onNodeWithTag(EditSheetTestTag)
            .fetchSemanticsNode().size.height

        assertTrue(
            "expected a wrapped sheet, took $sheetHeight of $screenHeight",
            sheetHeight < screenHeight * 0.45f,
        )
    }
}
