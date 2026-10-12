package com.diffuse.feature.editor.tools.multishot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.ShotPlacement
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
 * specs/multishot.md §9: the layout choice, a placed subject's controls, the time layout's layout
 * step, and a run waiting for a choice.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class MultiShotGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun multiShotSheetEmpty() {
        show(MultiShotState(open = true))

        capture("multishot_sheet_empty")
    }

    @Test
    fun multiShotSheetArrange() {
        show(
            MultiShotState(
                open = true,
                mode = MultiShotMode.Free,
                items = listOf(
                    ShotItem("a", ShotStatus.Ready, placement = ShotPlacement(opacity = 0.35f)),
                    ShotItem("b", ShotStatus.Ready, placement = ShotPlacement(scale = 1.2f, opacity = 0.65f)),
                ),
                selectedKey = "b",
            ),
        )

        capture("multishot_sheet_arrange")
    }

    @Test
    fun multiShotSheetTimeline() {
        show(timedHero())

        capture("multishot_sheet_timeline")
    }

    /** tasks.md 2026-10-02: one run, waiting for the choice on its second photo. */
    @Test
    fun multiShotSheetRun() {
        show(
            timedHero().copy(
                items = listOf(
                    ShotItem("a", ShotStatus.Ready, placement = ShotPlacement(opacity = 0.6f)),
                    ShotItem("b", ShotStatus.Selecting),
                ),
                selectedKey = "b",
                panel = TimelinePanel.Photos,
                laidOutOrder = null,
                candidateCount = 2,
                run = RunProgress(step = 3, total = 3, phase = RunPhase.Choosing),
            ),
        )

        capture("multishot_sheet_run")
    }

    private fun show(state: MultiShotState) {
        val actions = MultiShotActions(
            onAdd = {}, onReplace = {}, onSelect = {}, onRemove = {}, onMove = { _, _ -> },
            onExtract = {}, onChooseCandidate = {}, onPhraseChange = {}, onSubmitPhrase = {},
            onFinishExtraction = {}, onOpacityChange = {}, onScaleChange = {}, onRotationChange = {},
            onResetPlacement = {}, onAfterimage = {}, onRecheckServer = {}, onOpenSettings = {},
            onCancel = {}, onApply = {},
        )
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        MultiShotSheet(state = state, actions = actions)
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
}
