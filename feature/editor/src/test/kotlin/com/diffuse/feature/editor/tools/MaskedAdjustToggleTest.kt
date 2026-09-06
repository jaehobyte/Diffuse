package com.diffuse.feature.editor.tools

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.editor.tools.light.LightSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** specs/selection_tool.md §8.1. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskedAdjustToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private val plain = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
    private val withMask = plain.withMask(ImageRef("/mask_a.png"), id = "a")
    private val changes = mutableListOf<Boolean>()

    @Test
    fun `the toggle is absent without an active mask`() {
        show(plain, available = false, maskedOnly = true)

        compose.onNodeWithTag(MaskedOnlyToggleTestTag).assertDoesNotExist()
    }

    @Test
    fun `the toggle is on by default once a mask is applied`() {
        show(withMask, available = true, maskedOnly = true)

        compose.onNodeWithTag(MaskedOnlyToggleTestTag).assertExists()
        compose.onNodeWithTag(MaskedOnlyToggleTestTag).assertIsOn()
    }

    @Test
    fun `turning it off reports the change`() {
        show(withMask, available = true, maskedOnly = true)

        compose.onNodeWithTag(MaskedOnlyToggleTestTag).performClick()

        assertEquals(listOf(false), changes)
    }

    @Test
    fun `off shows the unmasked value, on shows the masked one`() {
        val document = withMask
            .withAdjust(AdjustKind.Exposure, 0.5f)
            .withAdjust(AdjustKind.Exposure, -0.25f, maskId = "a")

        assertEquals(0.5f, document.adjustValue(AdjustKind.Exposure), 0f)
        assertEquals(-0.25f, document.adjustValue(AdjustKind.Exposure, "a"), 0f)

        show(document, available = true, maskedOnly = false)
        compose.onNodeWithTag(MaskedOnlyToggleTestTag).assertIsOff()
    }

    private fun show(document: EditDocument, available: Boolean, maskedOnly: Boolean) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                LightSheet(
                    document = document,
                    onValueChange = { _, _ -> },
                    onValueChangeFinished = {},
                    onCancel = {},
                    onApply = {},
                    maskOption = MaskOption(
                        available = available,
                        maskedOnly = maskedOnly,
                        onMaskedOnlyChange = { changes += it },
                    ),
                )
            }
        }
    }
}
