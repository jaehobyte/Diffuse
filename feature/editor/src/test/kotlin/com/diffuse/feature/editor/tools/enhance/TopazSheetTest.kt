package com.diffuse.feature.editor.tools.enhance

import androidx.compose.ui.semantics.SemanticsProperties
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.ai.topaz.TopazFailure
import com.diffuse.core.ai.topaz.TopazOptions
import com.diffuse.core.ai.topaz.TopazPurpose
import com.diffuse.core.ui.ScreenshotOptions
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** specs/topaz_enhancement.md §2, §8: the shared sheet in each of its states. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class TopazSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = TopazActions(
        onPurpose = { calls += "purpose:$it" },
        onScale = { calls += "scale:$it" },
        onEstimate = { calls += "estimate" },
        onRun = { calls += "run" },
        onSave = { calls += "save" },
        onAdopt = { calls += "adopt" },
        onCompare = { calls += "compare:$it" },
        onOpenSettings = { calls += "settings" },
        onClose = { calls += "close" },
    )

    private val input = TopazInput(4096, 3072, hasAlpha = false, before = ImageBitmap(120, 90))

    private val setup = TopazState(
        group = TopazGroup.Enhance,
        purpose = TopazPurpose.Upscale,
        options = TopazOptions(scale = 2),
        configured = true,
        input = input,
    )

    private val withResult = setup.copy(
        result = TopazResult(
            file = File("/tmp/out.jpg"),
            width = 8192,
            height = 6144,
            mimeType = "image/jpeg",
            purpose = TopazPurpose.Upscale,
            options = TopazOptions(scale = 2),
            after = ImageBitmap(120, 90),
            credits = 2,
        ),
    )

    // ---- setup ------------------------------------------------------------

    @Test
    fun `setup is 취소 and 실행, with sizes, scope and the credit notice`() {
        show(setup)

        compose.onNodeWithText("취소").assertIsDisplayed()
        compose.onNodeWithText("실행").assertIsEnabled().performClick()
        compose.onNodeWithTag(TopazSizeTestTag).performScrollTo()
        compose.onNodeWithText("입력 4096×3072 → 출력 8192×6144").assertExists()
        compose.onNodeWithText("Topaz Labs에서 사진을 처리하며 API 크레딧을 사용합니다").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("예상 크레딧 확인").performScrollTo().performClick()
        assertEquals(listOf("run", "estimate"), calls)
    }

    @Test
    fun `no wire names, endpoints or free-preview wording reach the screen`() {
        show(withResult.copy(purpose = TopazPurpose.Sharpen, options = TopazOptions(scale = 1, sharpenStrength = 0.4f)))

        listOf("Standard V2", "Standard", "enhance", "process", "sharpenStrength", "무료", "미리보기").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `4x of a full working render is offered for upscale but greyed for restore`() {
        show(setup.copy(purpose = TopazPurpose.Restore, options = TopazOptions(scale = 1)))

        compose.onNodeWithTag(topazScaleTag(4)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(topazScaleTag(2)).assertIsEnabled()
        compose.onNodeWithText("생성형 모델이라", substring = true).assertExists()
    }

    @Test
    fun `without a key the feature is explained, settings are one tap away, and 실행 is off`() {
        show(setup.copy(configured = false))

        compose.onNodeWithText("실행").assertIsNotEnabled()
        compose.onNodeWithTag(TopazOpenSettingsTestTag).performScrollTo().performClick()
        compose.onNodeWithText("같은 구도에서 픽셀 수를 늘려요", substring = true).assertExists()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun `a failure says why and that a retry is possible`() {
        show(setup.copy(failure = TopazFailure.SubmitUnknown))

        compose.onNodeWithText("요청이 접수됐는지 알 수 없어요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("실행").assertIsEnabled()
    }

    @Test
    fun `an active selection is called out as not limiting the run`() {
        show(setup, hasSelection = true)

        compose.onNodeWithText("선택 영역이 있어도 사진 전체를 처리해요").performScrollTo().assertExists()
    }

    @Test
    fun `a progress the API reported is shown as reported`() {
        show(setup.copy(phase = TopazPhase.Processing, progress = 40))

        compose.onNodeWithText("처리 중 40%").performScrollTo().assertExists()
        compose.onNodeWithText("실행").assertIsNotEnabled()
    }

    // ---- result -----------------------------------------------------------

    @Test
    fun `a result is 닫기 and 적용, with 고해상도 저장 beside compare`() {
        show(withResult)

        compose.onNodeWithText("닫기").assertIsDisplayed().performClick()
        compose.onNodeWithText("적용").assertIsEnabled().performClick()
        compose.onNodeWithTag(TopazSaveTestTag).performScrollTo().performClick()
        compose.onNodeWithText("결과 8192×6144").assertExists()
        compose.onNodeWithText("긴 변 최대 4096px로 이어서 편집해요", substring = true).assertExists()
        compose.onNodeWithText("되돌리기로 되돌릴 수 있어요", substring = true).assertExists()
        compose.onNodeWithText("실행").assertDoesNotExist()
        assertEquals(listOf("close", "adopt", "save"), calls)
    }

    @Test
    fun `TalkBack can flip the compare without holding it`() {
        show(withResult)

        compose.onNodeWithTag(TopazCompareTestTag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "후"))
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("compare:true"), calls)
    }

    /** review R1/R2: a real press holds 전 for as long as it lasts, and release returns to 후. */
    @Test
    fun `a real press shows 전 while held and 후 after release`() {
        show(withResult)
        val button = compose.onNodeWithTag(TopazCompareTestTag).performScrollTo()

        button.performTouchInput { down(center) }
        assertEquals(listOf("compare:true"), calls)
        compose.mainClock.advanceTimeBy(HOLD_MS)
        assertEquals("a long hold is still a hold", listOf("compare:true"), calls)
        button.performTouchInput { up() }

        assertEquals(listOf("compare:true", "compare:false"), calls)
    }

    /** R1: a press the gesture system cancels (a scroll took it, the sheet closed) never sticks. */
    @Test
    fun `a cancelled press returns to 후`() {
        show(withResult)
        val button = compose.onNodeWithTag(TopazCompareTestTag).performScrollTo()

        button.performTouchInput { down(center) }
        button.performTouchInput { cancel() }

        assertEquals(listOf("compare:true", "compare:false"), calls)
    }

    /** R2: through state, as the screen wires it — the canvas label follows the press. */
    @Test
    fun `the canvas label follows a real press through the state`() {
        var state by mutableStateOf(withResult)
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.fillMaxSize()) { TopazCompareLabel(comparing = state.comparing) }
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        TopazSheet(
                            state = state,
                            hasSelection = false,
                            actions = actions.copy(onCompare = { state = state.copy(comparing = it) }),
                        )
                    }
                }
            }
        }
        val button = compose.onNodeWithTag(TopazCompareTestTag).performScrollTo()
        val label = compose.onNodeWithTag(TopazCompareLabelTestTag)
        label.assertTextEquals("후")

        button.performTouchInput { down(center) }
        compose.waitForIdle()
        label.assertTextEquals("전")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "전"))
        button.performTouchInput { up() }
        compose.waitForIdle()

        label.assertTextEquals("후")
    }

    @Test
    fun `a result from earlier settings is marked, and 실행 comes back`() {
        show(withResult.copy(options = TopazOptions(scale = 4)))

        compose.onNodeWithText("이전 설정의 결과예요", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("실행").assertIsEnabled()
        compose.onNodeWithText("적용").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a, fontScale = 2.0f)
    fun `at twice the font size the sheet stays within 45 percent and the actions stay reachable`() {
        show(withResult)

        compose.onNodeWithText("적용").assertIsDisplayed()
        compose.onNodeWithTag(TopazSaveTestTag).performScrollTo().assertIsDisplayed()
        val screen = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.heightPixels
        val sheet = compose.onNodeWithTag(TopazSheetTestTag).fetchSemanticsNode().size.height
        assertTrue("sheet took $sheet of $screen", sheet <= screen * 0.45f + 1f)
    }

    // ---- shortcuts --------------------------------------------------------

    @Test
    fun `the manual sheets carry the shortcut only when the host wires one`() {
        val document = com.diffuse.core.imaging.model.EditDocument(
            "d", com.diffuse.core.imaging.model.ImageRef("/p.jpg"), createdAt = 0L, updatedAt = 0L,
        )
        var opened: TopazGroup? = null
        var wired by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                com.diffuse.feature.editor.tools.ToolSheetHost(
                    selectedTool = com.diffuse.feature.editor.Tool.Detail,
                    document = document,
                    onValueChange = { _, _ -> },
                    onValueChangeFinished = {},
                    onCancel = {},
                    onApply = {},
                    onAiShortcut = if (wired) { group -> opened = group } else null,
                )
            }
        }
        compose.onNodeWithTag(TopazShortcutTestTag).assertDoesNotExist()

        wired = true
        compose.onNodeWithText("AI 화질 개선").performScrollTo().performClick()

        assertEquals(TopazGroup.Enhance, opened)
    }

    // ---- goldens ----------------------------------------------------------

    @Test
    fun topazSheetSetup() {
        show(setup.copy(estimate = TopazEstimateState.Known(4)))
        capture("topaz_sheet_setup")
    }

    @Test
    fun topazSheetResult() {
        show(withResult.copy(saved = true))
        capture("topaz_sheet_result")
    }

    @Test
    fun topazSheetNoKey() {
        show(
            TopazState(
                group = TopazGroup.Color,
                purpose = TopazPurpose.ColorBalance,
                options = TopazOptions(1),
                input = input,
            ),
        )
        capture("topaz_sheet_no_key")
    }

    private fun show(state: TopazState, hasSelection: Boolean = false) {
        compose.setContent {
            AppTheme(ThemeMode.Edit) {
                Box(modifier = Modifier.fillMaxSize().background(Tokens.editBackground)) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        TopazSheet(state = state, hasSelection = hasSelection, actions = actions)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val HOLD_MS = 12_000L
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(
            filePath = ScreenshotOptions.goldenPath(name),
            roborazziOptions = ScreenshotOptions.options,
        )
    }
}
