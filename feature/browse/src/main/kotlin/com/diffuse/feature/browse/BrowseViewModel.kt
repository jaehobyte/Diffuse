package com.diffuse.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.imaging.load.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** specs/browse.md: the list is live, so there is no pull-to-refresh. */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repository: ProjectRepository,
    loader: ImageLoader,
) : ViewModel() {

    val projects: StateFlow<List<ProjectSummary>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val importer = BrowseImport(loader, repository)

    fun duplicate(id: String) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
