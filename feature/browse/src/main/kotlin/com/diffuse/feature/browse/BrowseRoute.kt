package com.diffuse.feature.browse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.diffuse.core.common.AppError
import com.diffuse.core.data.ProjectSummary
import kotlinx.coroutines.launch

const val BrowseSnackbarTestTag = "BrowseSnackbar"

/**
 * Resources are read in composition, not from a `Context` inside the coroutine: the latter
 * is what `LocalContextGetResourceValueCall` flags, because it misses configuration changes.
 */
@Composable
private fun rememberErrorMessages(): (AppError) -> String {
    val tooLarge = stringResource(R.string.error_too_large)
    val unsupported = stringResource(R.string.error_unsupported)
    val missingSource = stringResource(R.string.error_missing_source)
    val io = stringResource(R.string.error_io)
    return remember(tooLarge, unsupported, missingSource, io) {
        { error ->
            when (error) {
                AppError.TooLarge -> tooLarge
                AppError.Unsupported -> unsupported
                AppError.MissingSource -> missingSource
                is AppError.Io -> io
            }
        }
    }
}

/**
 * specs/browse.md §Import. The Photo Picker needs no storage permission, so there is
 * nothing to request before launching it.
 */
@Composable
fun BrowseRoute(
    projects: List<ProjectSummary>,
    nowMillis: Long,
    importer: BrowseImport,
    onOpenEditor: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable BoxScope.(ProjectSummary) -> Unit = {},
) {
    val errorMessage = rememberErrorMessages()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            when (val outcome = importer.import(uri)) {
                is BrowseImport.Outcome.OpenEditor -> {
                    importing = false
                    onOpenEditor(outcome.projectId)
                }
                is BrowseImport.Outcome.Failed -> {
                    importing = false
                    // DESIGN.md §4: errors are a snackbar, never a toast.
                    snackbarHostState.showSnackbar(errorMessage(outcome.error))
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BrowseScreen(
            projects = projects,
            nowMillis = nowMillis,
            onOpen = onOpenEditor,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onNewProject = {
                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            },
            importing = importing,
            thumbnail = thumbnail,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.testTag(BrowseSnackbarTestTag).align(Alignment.BottomCenter),
        )
    }
}
