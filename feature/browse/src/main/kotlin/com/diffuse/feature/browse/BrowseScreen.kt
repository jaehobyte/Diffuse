package com.diffuse.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.ui.components.PrimaryPill
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §5: 2 columns, 8dp gap, 16dp side padding; 3 columns from 600dp. */
private val GridGap = 8.dp
private val ScreenPadding = 16.dp
private val TopBarHeight = 56.dp
private val CtaHeight = 48.dp
private const val WIDE_SCREEN_DP = 600
private const val WIDE_COLUMNS = 3
private const val PHONE_COLUMNS = 2

const val BrowseGridTestTag = "BrowseGrid"
const val BrowseEmptyTestTag = "BrowseEmpty"
const val BrowseCtaTestTag = "BrowseCta"
const val BrowseDeleteDialogTestTag = "BrowseDeleteDialog"

/**
 * specs/browse.md. Browse is the light half of DESIGN.md §1, so it fixes its own theme
 * mode for the same reason the editor does.
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
                    EmptyState(modifier = Modifier.weight(1f), onNewProject = onNewProject)
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
                PrimaryPill(
                    text = stringResource(R.string.browse_new_project),
                    onClick = onNewProject,
                    modifier = Modifier
                        .testTag(BrowseCtaTestTag)
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = ScreenPadding)
                        .height(CtaHeight),
                )
            }
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
            style = Typography.headingMd,
            color = colors.ink,
        )
    }
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
    val columns = if (LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP) {
        WIDE_COLUMNS
    } else {
        PHONE_COLUMNS
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.testTag(BrowseGridTestTag).fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = GridGap,
            bottom = ScreenPadding + CtaHeight + ScreenPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalItemSpacing = GridGap,
    ) {
        items(projects, key = { it.id }) { summary ->
            BrowseTile(
                summary = summary,
                nowMillis = nowMillis,
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

@Composable
private fun EmptyState(modifier: Modifier, onNewProject: () -> Unit) {
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
        Box(modifier = Modifier.height(24.dp))
        PrimaryPill(
            text = stringResource(R.string.browse_new_project),
            onClick = onNewProject,
            modifier = Modifier.testTag(BrowseCtaTestTag).height(CtaHeight),
        )
    }
}

@Composable
private fun DeleteConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(BrowseDeleteDialogTestTag),
        title = { Text(stringResource(R.string.browse_delete_title), style = Typography.headingMd) },
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
