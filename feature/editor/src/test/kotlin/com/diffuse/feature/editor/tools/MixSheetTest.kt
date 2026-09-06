package com.diffuse.feature.editor.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.history.HistoryStack
import com.diffuse.core.imaging.model.AdjustKind
import com.diffuse.core.imaging.model.EditDocument
import com.diffuse.core.imaging.model.HslBand
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.feature.editor.tools.mix.MixSheet
import com.diffuse.feature.editor.tools.mix.mixBandChipTag
import com.diffuse.feature.editor.tools.mix.mixKinds
import com.diffuse.feature.editor.tools.mix.swatchColor
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** specs/adjust_hsl.md §6, §7. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class MixSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val base = EditDocument("d", ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L)
    private lateinit var history: HistoryStack

    private fun showSheet(
        initial: EditDocument = base,
        maskOption: MaskOption = MaskOption.None,
    ) {
        history = HistoryStack(initial)
        compose.setContent {
            val document by history.current.collectAsState()
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    MixSheet(
                        document = document,
                        onValueChange = { kind, value ->
                            history.push(
                                document.withAdjust(kind, value, maskId = maskId(maskOption)),
                                coalesceKey = "adjust:$kind",
                            )
                        },
                        onValueChangeFinished = { history.commitCoalesce() },
                        onCancel = {},
                        onApply = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        maskOption = maskOption,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun maskId(option: MaskOption): String? =
        if (option.available && option.maskedOnly) history.current.value.activeMaskId else null

    @Test
    fun `the sheet opens on 빨강 and shows its three channels in order`() {
        showSheet()

        assertEquals(
            listOf(
                AdjustKind.HslRedHue,
                AdjustKind.HslRedSaturation,
                AdjustKind.HslRedLuminance,
            ),
            mixKinds(HslBand.Red),
        )
        mixKinds(HslBand.Red).forEach {
            compose.onNodeWithTag(adjustSliderTag(it)).assertExists()
        }
    }

    @Test
    fun `tapping a chip swaps in that band's sliders`() {
        showSheet()

        compose.onNodeWithTag(mixBandChipTag(HslBand.Blue)).performClick()
        compose.waitForIdle()

        mixKinds(HslBand.Blue).forEach {
            compose.onNodeWithTag(adjustSliderTag(it)).assertExists()
        }
        compose.onNodeWithTag(adjustSliderTag(AdjustKind.HslRedHue)).assertDoesNotExist()
    }

    @Test
    fun `the sliders show the selected band's stored value, not the previous band's`() {
        showSheet(base.withAdjust(AdjustKind.HslBlueSaturation, 0.4f))

        compose.onNodeWithText("+40").assertDoesNotExist()
        compose.onNodeWithTag(mixBandChipTag(HslBand.Blue)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("+40").assertExists()
    }

    @Test
    fun `a drag collapses into one history entry once released`() {
        showSheet()

        compose.onNodeWithTag(adjustSliderTag(AdjustKind.HslRedSaturation))
            .performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertTrue(
            "expected the drag to move 빨강 채도",
            history.current.value.adjustValue(AdjustKind.HslRedSaturation) != 0f,
        )

        history.undo()
        assertEquals(base, history.current.value)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun `with a selection the toggle appears and the adjustment carries the maskId`() {
        val masked = base.withMask(ImageRef("/mask_a.png"), id = "a")
        showSheet(
            masked,
            MaskOption(available = true, maskedOnly = true, onMaskedOnlyChange = {}),
        )

        compose.onNodeWithTag(MaskedOnlyToggleTestTag).assertExists()
        compose.onNodeWithTag(adjustSliderTag(AdjustKind.HslRedSaturation))
            .performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals(0f, history.current.value.adjustValue(AdjustKind.HslRedSaturation), 0f)
        assertTrue(
            "the adjustment must be scoped to the selection",
            history.current.value.adjustValue(AdjustKind.HslRedSaturation, "a") != 0f,
        )
    }

    @Test
    fun `no band swatch is the accent`() {
        // specs/adjust_hsl.md §7: the sheet's one accent stays on 적용.
        HslBand.entries.forEach { band ->
            assertTrue(
                "${band.name}'s swatch must not be the accent token",
                band.swatchColor() != Tokens.accent,
            )
        }
    }
}
