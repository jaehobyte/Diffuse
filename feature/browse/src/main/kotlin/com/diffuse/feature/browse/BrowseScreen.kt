package com.diffuse.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.OverlayTextShadow
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §5: the Gallery grid — 4 square columns, 1dp gaps, 8dp sides; 6 columns from 600dp. */
private val GridGap = 1.dp
private val GridSidePadding = 8.dp
private val ScreenPadding = 16.dp
private val TopBarHeight = 56.dp
private val SectionTopPadding = 20.dp
private val SectionBottomPadding = 8.dp
/** DESIGN.md §4: the floating pill the Gallery keeps at the bottom; the grid leaves room under it. */
private val FloatingBarHeight = 56.dp
private val FloatingBarPadding = 20.dp
private val FloatingIconSize = 24.dp
private val FloatingIconGap = 8.dp
private const val WIDE_SCREEN_DP = 600
private const val WIDE_COLUMNS = 6
private const val PHONE_COLUMNS = 4
private const val FLOATING_BAR_ALPHA = 0.92f

const val BrowseGridTestTag = "BrowseGrid"
const val BrowseEmptyTestTag = "BrowseEmpty"
const val BrowseCtaTestTag = "BrowseCta"
const val BrowseDeleteDialogTestTag = "BrowseDeleteDialog"
const val BrowseImportOverlayTestTag = "BrowseImportOverlay"

private const val IMPORT_SCRIM_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.38f

/**
 * specs/browse.md, laid out like the Galaxy Gallery's 사진 tab (DESIGN.md §4): day headers over a
 * tight grid of square thumbnails, and a floating pill at the bottom for the one action.
 */
@Composable
fun BrowseScreen(
    projects: List<ProjectSummary>,
    nowMillis: Long,
    onOpen: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
    /** specs/browse.md: a 40% scrim with progress while a pick is being decoded. */
    importing: Boolean = false,
    thumbnail: @Composable BoxScope.(ProjectSummary) -> Unit = {},
) {
    AppTheme(mode = ThemeMode.Browse) {
        val colors = LocalAppColors.current
        var actionsFor by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<String?>(null) }

        Box(modifier = modifier.fillMaxSize().background(colors.background)) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar()
                if (projects.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.weight(1f),
                        enabled = !importing,
                        onNewProject = onNewProject,
                    )
                } else {
                    Grid(
                        projects = projects,
                        nowMillis = nowMillis,
                        actionsFor = actionsFor,
                        onOpen = { actionsFor = null; onOpen(it) },
                        onLongPress = { actionsFor = it },
                        onDuplicate = { actionsFor = null; onDuplicate(it) },
                        onDelete = { pendingDelete = it },
                        modifier = Modifier.weight(1f),
                        thumbnail = thumbnail,
                    )
                }
            }
            if (projects.isNotEmpty()) {
                FloatingBar(
                    onClick = onNewProject,
                    enabled = !importing,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = FloatingBarPadding),
                )
            }

            if (importing) ImportOverlay()
        }

        // DESIGN.md §4: confirmation dialogs only for destructive actions.
        pendingDelete?.let { id ->
            DeleteConfirmation(
                onConfirm = { pendingDelete = null; actionsFor = null; onDelete(id) },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

@Composable
private fun TopBar() {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = ScreenPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.browse_app_mark),
            style = Typography.headingLg,
            color = colors.ink,
        )
    }
}

/** One day's worth of projects under its header; newest project first, newest day first. */
private data class DaySection(val label: String, val projects: List<ProjectSummary>)

@Composable
private fun daySections(projects: List<ProjectSummary>, nowMillis: Long): List<DaySection> {
    val context = LocalContext.current
    val today = DayLabel.dayOf(nowMillis)
    return projects
        .sortedByDescending { it.updatedAt }
        .groupBy { DayLabel.dayOf(it.updatedAt) }
        .map { (day, items) -> DaySection(DayLabel.format(context, day, today), items) }
}

@Composable
private fun Grid(
    projects: List<ProjectSummary>,
    nowMillis: Long,
    actionsFor: String?,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable BoxScope.(ProjectSummary) -> Unit,
) {
    val colors = LocalAppColors.current
    val columns = if (LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP) {
        WIDE_COLUMNS
    } else {
        PHONE_COLUMNS
    }
    val sections = daySections(projects, nowMillis)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.testTag(BrowseGridTestTag).fillMaxSize(),
        contentPadding = PaddingValues(
            start = GridSidePadding,
            end = GridSidePadding,
            bottom = FloatingBarPadding + FloatingBarHeight + FloatingBarPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        sections.forEach { section ->
            item(key = "day:${section.label}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = section.label,
                    style = Typography.headingLg,
                    color = colors.ink,
                    modifier = Modifier.padding(top = SectionTopPadding, bottom = SectionBottomPadding),
                )
            }
            items(section.projects, key = { it.id }) { summary ->
                BrowseTile(
                    summary = summary,
                    showActions = actionsFor == summary.id,
                    onOpen = { onOpen(summary.id) },
                    onLongPress = { onLongPress(summary.id) },
                    onDuplicate = { onDuplicate(summary.id) },
                    onDelete = { onDelete(summary.id) },
                    thumbnail = { thumbnail(summary) },
                )
            }
        }
    }
}

/**
 * DESIGN.md §4: the Gallery's floating bottom pill — a `surfaceRaised` pill with a white glyph and
 * label — carrying the one action Browse has.
 */
@Composable
private fun FloatingBar(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .testTag(BrowseCtaTestTag)
            .heightIn(min = FloatingBarHeight)
            .clip(Shapes.pill)
            .background(colors.surfaceRaised.copy(alpha = FLOATING_BAR_ALPHA))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FloatingBarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = colors.ink.copy(alpha = alpha),
            modifier = Modifier.size(FloatingIconSize),
        )
        Spacer(modifier = Modifier.width(FloatingIconGap))
        Text(
            text = stringResource(R.string.browse_new_project),
            style = Typography.bodyStrong,
            color = colors.ink.copy(alpha = alpha),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, enabled: Boolean, onNewProject: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .testTag(BrowseEmptyTestTag)
            .fillMaxSize()
            .padding(ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.browse_empty_title),
            style = Typography.headingXl,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Box(modifier = Modifier.height(Dimens.touchTarget / 2))
        FloatingBar(onClick = onNewProject, enabled = enabled)
    }
}

/** DESIGN.md §4 (State display): 40% scrim, accent circular progress, one line of overlay text. */
@Composable
private fun BoxScope.ImportOverlay() {
    Column(
        modifier = Modifier
            .testTag(BrowseImportOverlayTestTag)
            .matchParentSize()
            .background(Color.Black.copy(alpha = IMPORT_SCRIM_ALPHA)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Tokens.accent)
        Box(modifier = Modifier.height(SectionBottomPadding))
        Text(
            text = stringResource(R.string.browse_importing),
            style = Typography.bodyMd.copy(shadow = OverlayTextShadow),
            color = Tokens.canvasOverlayInk,
        )
    }
}

@Composable
private fun DeleteConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(BrowseDeleteDialogTestTag),
        title = { Text(stringResource(R.string.browse_delete_title), style = Typography.headingLg) },
        text = { Text(stringResource(R.string.browse_delete_message), style = Typography.bodyMd) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.browse_delete_confirm), style = Typography.bodyStrong)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browse_delete_cancel), style = Typography.bodyStrong)
            }
        },
    )
}
