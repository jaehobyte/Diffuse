package com.diffuse.feature.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.data.ProjectSummary
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6a)
class BrowseScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val duplicated = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var newProjectClicks = 0

    private fun showBrowse(projects: List<ProjectSummary> = sampleProjects) {
        compose.setContent {
            BrowseScreen(
                projects = projects,
                nowMillis = NOW,
                onOpen = { opened += it },
                onDuplicate = { duplicated += it },
                onDelete = { deleted += it },
                onNewProject = { newProjectClicks++ },
                thumbnail = { fakeThumbnail(it) },
            )
        }
        compose.waitForIdle()
    }

    private fun string(resId: Int) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)

    @Test
    fun `tapping a tile opens the project`() {
        showBrowse()

        compose.onNodeWithTag(tileTag("1")).performClick()

        assertEquals(listOf("1"), opened)
    }

    @Test
    fun `long press reveals duplicate and delete`() {
        showBrowse()
        compose.onNodeWithTag(tileActionTag("2", "delete")).assertDoesNotExist()

        compose.onNodeWithTag(tileTag("2")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(tileActionTag("2", "duplicate")).assertExists()
        compose.onNodeWithTag(tileActionTag("2", "delete")).assertExists()
    }

    @Test
    fun `delete asks for confirmation before reporting`() {
        showBrowse()
        compose.onNodeWithTag(tileTag("2")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(tileActionTag("2", "delete")).performClick()
        compose.waitForIdle()
        assertEquals("delete must not fire before confirmation", emptyList<String>(), deleted)

        compose.onNodeWithText(string(R.string.browse_delete_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("2"), deleted)
    }

    @Test
    fun `dismissing the confirmation keeps the project`() {
        showBrowse()
        compose.onNodeWithTag(tileTag("3")).performTouchInput { longClick() }
        compose.onNodeWithTag(tileActionTag("3", "delete")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.browse_delete_cancel)).performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun `duplicate reports immediately`() {
        showBrowse()
        compose.onNodeWithTag(tileTag("4")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(tileActionTag("4", "duplicate")).performClick()

        assertEquals(listOf("4"), duplicated)
    }

    @Test
    fun `the empty state offers the same primary action`() {
        showBrowse(projects = emptyList())

        compose.onNodeWithTag(BrowseEmptyTestTag).assertExists()
        compose.onNodeWithText(string(R.string.browse_empty_title)).assertExists()
        compose.onNodeWithTag(BrowseCtaTestTag).performClick()

        assertEquals(1, newProjectClicks)
    }

    @Test
    fun `relative time reads in Korean`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("방금", RelativeTime.format(context, NOW, NOW))
        assertEquals("3분 전", RelativeTime.format(context, NOW - 3 * 60_000L, NOW))
        assertEquals("2시간 전", RelativeTime.format(context, NOW - 2 * 3_600_000L, NOW))
        assertEquals("어제", RelativeTime.format(context, NOW - 26 * 3_600_000L, NOW))
        assertEquals("3일 전", RelativeTime.format(context, NOW - 3 * 86_400_000L, NOW))
    }
}
