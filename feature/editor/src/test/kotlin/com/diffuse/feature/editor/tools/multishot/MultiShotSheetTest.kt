package com.diffuse.feature.editor.tools.multishot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.TimelineArrangement
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement
import com.diffuse.core.ui.components.EditSheetTestTag
import com.diffuse.feature.editor.EditorScreen
import com.diffuse.feature.editor.R
import com.diffuse.feature.editor.Tool
import com.diffuse.feature.editor.ToolGroup
import com.diffuse.feature.editor.ToolLevelState
import com.diffuse.feature.editor.ToolMenuProfile
import com.diffuse.feature.editor.ToolStripTestTag
import com.diffuse.feature.editor.canvas.testImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * specs/multishot.md §2, §9 requirement 28 (UI). The sheet reads its state and nothing else: the
 * empty invitation, a photo's extraction with where it goes, the choice among several answers, a
 * placed subject's labelled controls, the error line, and 적용 gated on something to apply — and
 * the tool is reachable from both menu profiles.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class MultiShotSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `a new composite chooses its layout before anything else`() {
        show(MultiShotState(open = true))

        compose.onNodeWithText(string(R.string.multishot_mode_question)).assertExists()
        compose.onNodeWithTag(MultiShotAddTestTag).assertDoesNotExist()
        compose.onNodeWithTag(MultiShotTimelineModeTestTag).performClick()
        compose.onNodeWithTag(MultiShotFreeModeTestTag).performClick()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("mode:Timeline", "mode:Free"), calls)
    }

    @Test
    fun `the time layout offers one run in the shown order, labels steps and keeps 적용 off`() {
        show(
            MultiShotState(
                open = true,
                mode = MultiShotMode.Timeline,
                items = listOf(ShotItem("a", ShotStatus.Picked), ShotItem("b", ShotStatus.Picked)),
                timeOrder = listOf("b", "a"),
                hero = ShotItem(MultiShotState.HERO_KEY, ShotStatus.Picked),
                selectedKey = "a",
                serverHost = HOST,
            ),
        )

        compose.onNodeWithText(string(R.string.multishot_timeline_guide)).assertExists()
        compose.onNodeWithText(context().getString(R.string.multishot_step, 1)).assertExists()
        compose.onNodeWithText(context().getString(R.string.multishot_step, 2)).assertExists()
        // Sending is said before the one press that sends; no separate order check is asked for.
        compose.onNodeWithText(context().getString(R.string.multishot_run_notice, HOST)).assertExists()
        compose.onNodeWithText("이 순서가 맞아요").assertDoesNotExist()
        compose.onNodeWithTag(MultiShotExtractTestTag).assertDoesNotExist()
        compose.onNodeWithTag(MultiShotRunTestTag).performScrollTo()
            .assert(hasText(string(R.string.multishot_run_all))).performClick()
        compose.onNodeWithTag(MultiShotReverseTestTag).performScrollTo().performClick()
        // "a" is second in time: it can go earlier, not later.
        // performScrollTo only scrolls the nearest scrollable parent — here the pill row — so the
        // row is brought into the sheet first; the click itself is a real touch.
        compose.onNode(hasAnyChild(hasTestTag(MultiShotEarlierTestTag)) and hasScrollAction()).performScrollTo()
        compose.onNodeWithTag(MultiShotEarlierTestTag).performClick()
        compose.onNodeWithTag(MultiShotLaterTestTag).assertIsNotEnabled()
        // The free layout's preset and drawing-order buttons are not offered here.
        compose.onNodeWithTag(MultiShotAfterimageTestTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.multishot_to_front)).assertDoesNotExist()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("run", "reverse", "time:a:true"), calls)
    }

    @Test
    fun `the run stays on offer whichever tile or step is shown`() {
        val base = timedHero().copy(timeOrder = listOf("a", "b"), laidOutOrder = null, orderConfirmed = false)
        var state by mutableStateOf(base)
        compose.setContent { MultiShotSheet(state, actions()) }
        listOf(
            base,
            base.copy(panel = TimelinePanel.Photos, selectedKey = "b"),
            base.copy(panel = TimelinePanel.Photos, selectedKey = MultiShotState.HERO_KEY),
        ).forEach { shown ->
            state = shown
            compose.waitForIdle()
            compose.onNodeWithTag(MultiShotRunTestTag).performScrollTo()
                .assert(hasText(string(R.string.multishot_run_place))).performClick()
        }
        assertEquals(listOf("run", "run", "run"), calls)
    }

    @Test
    fun `a run waiting for a choice says so, offers the candidates and 진행 취소, and nothing else moves`() {
        show(
            timedHero().copy(
                items = listOf(ShotItem("a", ShotStatus.Ready), ShotItem("b", ShotStatus.Selecting)),
                selectedKey = "b",
                panel = TimelinePanel.Photos,
                candidateCount = 2,
                run = RunProgress(step = 2, total = 2, phase = RunPhase.Choosing),
            ),
        )

        val progress = context().getString(
            R.string.multishot_run_progress, 2, 2, string(R.string.multishot_run_choosing),
        )
        compose.onNodeWithTag(MultiShotProgressTestTag).assert(hasText(progress))
        compose.onNodeWithTag(MultiShotRunTestTag).assertDoesNotExist()
        compose.onNodeWithTag(MultiShotAddTestTag).assertIsNotEnabled()
        compose.onNodeWithTag(MultiShotLayoutPanelTestTag).assertIsNotEnabled()
        compose.onNode(hasAnyChild(hasTestTag(multiShotCandidateTag(1))) and hasScrollAction()).performScrollTo()
        compose.onNodeWithTag(multiShotCandidateTag(1)).performScrollTo().performClick()
        compose.onNodeWithTag(MultiShotFinishTestTag).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasAnyChild(hasTestTag(MultiShotCancelRunTestTag)) and hasScrollAction()).performScrollTo()
        compose.onNodeWithTag(MultiShotCancelRunTestTag).performScrollTo().performClick()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("candidate:1", "cancelRun"), calls)
    }

    @Test
    fun `an unreadable photo is named by its step and blocks the run`() {
        val base = timedHero()
        show(
            base.copy(
                items = base.items.map {
                    if (it.key != "b") return@map it
                    it.copy(status = ShotStatus.Unreadable, problem = R.string.multishot_import_failed)
                },
                selectedKey = "b",
                panel = TimelinePanel.Photos,
            ),
        )

        compose.onNodeWithText(context().getString(R.string.multishot_unreadable_step, 2)).assertExists()
        compose.onNodeWithTag(MultiShotRunTestTag).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasAnyChild(hasTestTag(MultiShotEarlierTestTag)) and hasScrollAction()).performScrollTo()
        compose.onNodeWithText(string(R.string.multishot_replace)).performScrollTo().performClick()
        assertEquals(listOf("replace:b"), calls)
    }

    @Test
    fun `a composition changed since the layout says so and offers the rest of the run`() {
        val base = timedHero()
        show(
            base.copy(
                items = base.items + ShotItem("c", ShotStatus.Picked),
                timeOrder = base.timeOrder + "c",
                orderConfirmed = false,
            ),
        )

        compose.onNodeWithText(string(R.string.multishot_layout_stale)).assertExists()
        compose.onNodeWithTag(MultiShotRunTestTag).performScrollTo()
            .assert(hasText(string(R.string.multishot_run_rest)))
    }

    @Test
    fun `with an afterimage selected the layout step is one tap away`() {
        show(timedHero().copy(panel = TimelinePanel.Photos))

        compose.onNodeWithTag(MultiShotLayoutPanelTestTag).performScrollTo().performClick()
        assertEquals(listOf("panel:Layout"), calls)
    }

    @Test
    fun `the layout step offers the even arrangement for the count and says what it overwrites`() {
        // The order was just reversed: not yet settled, so both answers are offered.
        show(timedHero().copy(timeOrder = listOf("b", "a"), orderConfirmed = false))

        compose.onNodeWithTag(MultiShotTotalTestTag)
            .assert(hasText(context().getString(R.string.multishot_total, 3, 6)))
        compose.onNodeWithText(string(R.string.multishot_even_hint)).performScrollTo().assertExists()
        compose.onNodeWithTag(MultiShotPlaceTestTag).performScrollTo()
            .assert(hasText(context().getString(R.string.multishot_place_even, 3)))
            .performClick()
        compose.onNodeWithTag(MultiShotKeepTestTag).performScrollTo().performClick()
        compose.onNodeWithTag(MultiShotSpacingTestTag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        // No direction arrow: the hero is the newest but sits in the middle.
        compose.onNodeWithTag(multiShotDirectionTag(0f)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.multishot_layout_overwrites)).performScrollTo().assertExists()
        compose.onNodeWithTag(MultiShotPathTestTag).performScrollTo().performClick()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("place", "keep", "arrangement:Path"), calls)
    }

    @Test
    fun `an even count says the hero takes the left of the two middles`() {
        val base = timedHero()
        show(base.copy(items = base.items + ShotItem("c", ShotStatus.Ready, anchor = NormPoint(0.5f, 1f))))

        compose.onNodeWithText(string(R.string.multishot_even_even_count)).performScrollTo().assertExists()
    }

    @Test
    fun `the path arrangement keeps its direction and distance`() {
        show(timedHero().let { it.copy(layout = it.layout.copy(arrangement = TimelineArrangement.Path)) })

        compose.onNodeWithTag(MultiShotDistanceTestTag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50%"))
        compose.onNode(hasAnyChild(hasTestTag(multiShotDirectionTag(180f))) and hasScrollAction()).performScrollTo()
        compose.onNodeWithTag(multiShotDirectionTag(180f)).performClick()
        compose.onNodeWithTag(MultiShotSpacingTestTag).assertDoesNotExist()
        assertEquals(listOf("direction:180"), calls)
    }

    @Test
    fun `settled positions offer placing again and 적용`() {
        show(timedHero())

        compose.onNodeWithTag(MultiShotPlaceTestTag).performScrollTo()
            .assert(hasText(string(R.string.multishot_place_again)))
        compose.onNodeWithTag(MultiShotKeepTestTag).assertIsNotEnabled()
        compose.onNodeWithText(APPLY).assertIsEnabled()
    }

    @Test
    fun `a photo still to extract is named and blocks placing`() {
        val base = timedHero()
        show(base.copy(items = base.items.map { if (it.key == "b") it.copy(status = ShotStatus.Picked) else it }))

        compose.onNodeWithTag(MultiShotBlockerTestTag)
            .assert(hasText(context().getString(R.string.multishot_needs_extraction, 2)))
        compose.onNodeWithTag(MultiShotPlaceTestTag).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the empty sheet invites a photo and cannot apply`() {
        show(MultiShotState(open = true, mode = MultiShotMode.Free))

        compose.onNodeWithText(string(R.string.multishot_empty)).assertExists()
        compose.onNodeWithTag(MultiShotAddTestTag).performClick()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun `a photo says where it will be sent before it is sent`() {
        show(
            MultiShotState(
                open = true,
                mode = MultiShotMode.Free,
                items = listOf(ShotItem("a", ShotStatus.Picked, problem = R.string.multishot_server_unreachable)),
                selectedKey = "a",
                serverHost = HOST,
                server = MultiShotServer.Unreachable,
            ),
        )

        compose.onNodeWithText(context().getString(R.string.multishot_upload_notice, HOST)).assertExists()
        compose.onNodeWithTag(MultiShotStatusTestTag).assert(hasText(string(R.string.multishot_server_unreachable)))
        compose.onNodeWithText(string(R.string.multishot_recheck)).performScrollTo().performClick()
        compose.onNodeWithTag(MultiShotExtractTestTag).performScrollTo().performClick()
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
        assertEquals(listOf("recheck", "extract"), calls)
    }

    @Test
    fun `several answers are offered and 추출 완료 waits for a choice`() {
        val selecting = MultiShotState(
            open = true,
            mode = MultiShotMode.Free,
            items = listOf(ShotItem("a", ShotStatus.Selecting)),
            selectedKey = "a",
            photo = testImage(),
            candidateCount = 2,
        )
        show(selecting)

        compose.onNodeWithTag(MultiShotFinishTestTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(multiShotCandidateTag(1)).performScrollTo().performClick()
        assertEquals(listOf("candidate:1"), calls)
    }

    @Test
    fun `a placed subject shows labelled controls and 적용 is enabled`() {
        show(arranged())

        compose.onNodeWithTag(MultiShotOpacityTestTag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "35%"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(string(R.string.multishot_opacity)),
                ),
            )
        compose.onNodeWithTag(MultiShotScaleTestTag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150%"))
        compose.onNodeWithTag(MultiShotAfterimageTestTag).performScrollTo().performClick()
        compose.onNodeWithText(APPLY).assertIsEnabled()
        assertEquals(listOf("afterimage"), calls)
    }

    @Test
    fun `멀티샷 opens from the AI level of the general menu`() = opensFrom(ToolMenuProfile.General)

    @Test
    fun `멀티샷 opens from the AI level of the portrait menu`() = opensFrom(ToolMenuProfile.Portrait)

    private fun opensFrom(profile: ToolMenuProfile) {
        showEditor(profile)
        compose.onNodeWithTag(EditSheetTestTag).assertDoesNotExist()

        // The strip is lazy: bring the item in before tapping it, as a thumb would.
        compose.onNodeWithTag(ToolStripTestTag).performScrollToNode(hasTestTag(string(Tool.MultiShot.labelRes)))
        compose.onNodeWithTag(string(Tool.MultiShot.labelRes)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MultiShotSheetTestTag).assertExists()
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun arranged() = MultiShotState(
        open = true,
        mode = MultiShotMode.Free,
        items = listOf(
            ShotItem("a", ShotStatus.Ready, placement = ShotPlacement(scale = 1.5f, opacity = 0.35f)),
            ShotItem("b", ShotStatus.Ready, placement = ShotPlacement(opacity = 0.65f)),
        ),
        selectedKey = "a",
        draftShots = listOf(Shot("a", ImageRef("/p/shot_a.png"), 40, 30)),
    )

    private fun show(state: MultiShotState) {
        calls.clear()
        compose.setContent { MultiShotSheet(state, actions()) }
        compose.waitForIdle()
    }

    private fun showEditor(profile: ToolMenuProfile) {
        compose.setContent {
            var selected by remember { mutableStateOf<Tool?>(null) }
            EditorScreen(
                preview = testImage(),
                selectedTool = selected,
                onToolClick = { selected = if (selected == it) null else it },
                canUndo = false,
                canRedo = false,
                canCompare = false,
                onBack = {},
                onUndo = {},
                onRedo = {},
                onCompareChange = {},
                onExport = {},
                toolLevel = ToolLevelState(level = ToolGroup.Ai, profile = profile),
                sheet = { if (selected == Tool.MultiShot) MultiShotSheet(MultiShotState(open = true), actions()) },
            )
        }
        compose.waitForIdle()
    }

    private fun actions() = MultiShotActions(
        onAdd = { calls += "add" },
        onReplace = { calls += "replace:$it" },
        onSelect = { calls += "select:$it" },
        onRemove = { calls += "remove:$it" },
        onMove = { key, front -> calls += "move:$key:$front" },
        onExtract = { calls += "extract" },
        onChooseCandidate = { calls += "candidate:$it" },
        onPhraseChange = {},
        onSubmitPhrase = { calls += "phrase" },
        onFinishExtraction = { calls += "finish" },
        onOpacityChange = {},
        onScaleChange = {},
        onRotationChange = {},
        onResetPlacement = { calls += "reset" },
        onAfterimage = { calls += "afterimage" },
        onRecheckServer = { calls += "recheck" },
        onOpenSettings = { calls += "settings" },
        onCancel = { calls += "cancel" },
        onApply = { calls += "apply" },
        onModeChange = { calls += "mode:$it" },
        onMoveInTime = { key, earlier -> calls += "time:$key:$earlier" },
        onReverseTime = { calls += "reverse" },
        onRun = { calls += "run" },
        onCancelRun = { calls += "cancelRun" },
        onPlace = { calls += "place" },
        onKeepPositions = { calls += "keep" },
        onPanelChange = { calls += "panel:$it" },
        onArrangementChange = { calls += "arrangement:$it" },
        onSpacingChange = { calls += "spacing" },
        onDirectionChange = { calls += "direction:${it.toInt()}" },
        onAnchorEditingChange = { calls += "anchor:$it" },
    )

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(id: Int): String = context().getString(id)

    private companion object {
        const val HOST = "http://sam.example:8000"
        const val APPLY = "적용"
    }
}
