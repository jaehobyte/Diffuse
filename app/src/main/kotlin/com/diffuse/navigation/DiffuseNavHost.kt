package com.diffuse.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.diffuse.R
import com.diffuse.feature.browse.BrowseRoute
import com.diffuse.feature.editor.EditorRoute
import com.diffuse.feature.editor.EditorViewModel
import com.diffuse.feature.export.ExportProgressOverlay
import com.diffuse.feature.export.ExportSettings
import com.diffuse.feature.export.ExportSettingsStore
import com.diffuse.feature.export.ExportSheet
import com.diffuse.feature.export.Exporter
import com.diffuse.feature.export.autoFormatFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** specs/architecture.md §7: Browse → Editor → Export sheet. No deep links in v1. */
@Serializable
object BrowseRouteKey

@Serializable
data class EditorRouteKey(val projectId: String)

const val ExportConfirmDialogTestTag = "ExportAbortDialog"

@Composable
fun DiffuseNavHost(
    exporter: Exporter,
    exportSettingsStore: ExportSettingsStore,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = BrowseRouteKey,
        modifier = modifier.fillMaxSize(),
    ) {
        composable<BrowseRouteKey> {
            BrowseRoute(
                onOpenEditor = { projectId -> navController.navigate(EditorRouteKey(projectId)) },
            )
        }
        composable<EditorRouteKey> { entry ->
            // The same instance the route uses, so export reads the live document without
            // feature:editor ever depending on feature:export (specs/architecture.md §4.1).
            val viewModel = hiltViewModel<EditorViewModel>(entry)
            EditorWithExport(
                viewModel = viewModel,
                exporter = exporter,
                settingsStore = exportSettingsStore,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun EditorWithExport(
    viewModel: EditorViewModel,
    exporter: Exporter,
    settingsStore: ExportSettingsStore,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sheetOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var confirmAbort by remember { mutableStateOf(false) }

    val savedMessage = stringResource(R.string.export_saved_message)
    val failedMessage = stringResource(R.string.export_failed_message)

    fun startExport() {
        val document = state.document ?: return
        sheetOpen = false
        settingsStore.save(settings)
        progress = 0f
        exportJob = scope.launch {
            val outcome = exporter.export(document, settings) { progress = it }
            exportJob = null
            snackbarHostState.showSnackbar(
                when (outcome) {
                    is Exporter.Outcome.Saved -> savedMessage
                    is Exporter.Outcome.Failed -> failedMessage
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        EditorRoute(
            onBack = onBack,
            onExport = {
                settings = settings.autoFormatFor(state.document?.hasAlpha == true)
                sheetOpen = true
            },
        )
        if (sheetOpen) {
            ExportSheetSlot(
                settings = settings,
                onSettingsChange = { settings = it },
                onCancel = { sheetOpen = false },
                onSave = ::startExport,
            )
        }
        if (exportJob != null) {
            ExportProgressOverlay(progress = progress, onCancel = { exportJob?.cancel() })
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // specs/editor_shell.md: back with an export running is a destructive confirmation.
    BackHandler(enabled = true) {
        when (backAction(exportJob != null, sheetOpen, state.selectedTool != null)) {
            BackAction.ConfirmAbortExport -> confirmAbort = true
            BackAction.CloseExportSheet -> sheetOpen = false
            BackAction.CancelTool -> viewModel.cancelSheet()
            BackAction.LeaveEditor -> scope.launch {
                viewModel.onLeave()
                onBack()
            }
        }
    }

    if (confirmAbort) {
        AbortExportDialog(
            onConfirm = {
                exportJob?.cancel()
                exportJob = null
                confirmAbort = false
                scope.launch {
                    viewModel.onLeave()
                    onBack()
                }
            },
            onDismiss = { confirmAbort = false },
        )
    }
}

@Composable
private fun AbortExportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ExportConfirmDialogTestTag),
        title = { Text(stringResource(R.string.export_abort_title)) },
        text = { Text(stringResource(R.string.export_abort_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.export_abort_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.export_abort_cancel))
            }
        },
    )
}

/** DESIGN.md §4: the export sheet rises from the bottom, over the editor. */
@Composable
private fun BoxScope.ExportSheetSlot(
    settings: ExportSettings,
    onSettingsChange: (ExportSettings) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
        ExportSheet(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onCancel = onCancel,
            onSave = onSave,
        )
    }
}
