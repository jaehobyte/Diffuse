package com.diffuse.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.diffuse.core.data.ProjectSummary

internal const val NOW = 1_700_000_000_000L

/** Mixed aspects, as specs/browse.md's `browse_grid` golden asks for. */
internal val sampleProjects = listOf(
    project("1", widthPx = 400, heightPx = 300, minutesAgo = 3),
    project("2", widthPx = 300, heightPx = 400, minutesAgo = 90),
    project("3", widthPx = 400, heightPx = 400, minutesAgo = 60 * 26),
    project("4", widthPx = 300, heightPx = 500, minutesAgo = 60 * 24 * 3),
    project("5", widthPx = 500, heightPx = 300, minutesAgo = 0),
    project("6", widthPx = 400, heightPx = 520, minutesAgo = 60 * 5),
)

internal fun project(id: String, widthPx: Int, heightPx: Int, minutesAgo: Int) = ProjectSummary(
    id = id,
    createdAt = NOW - minutesAgo * 60_000L,
    updatedAt = NOW - minutesAgo * 60_000L,
    widthPx = widthPx,
    heightPx = heightPx,
    thumbPath = "/tmp/thumb-$id.png",
)

/** Deterministic stand-in for the Coil thumbnail; the grid is what is under test. */
@Composable
internal fun BoxScope.fakeThumbnail(summary: ProjectSummary) {
    val palette = listOf(
        Color(0xFFE60023), Color(0xFF1E7A46), Color(0xFFB8741A),
        Color(0xFF3355EE), Color(0xFF5F5F5A), Color(0xFF87CEEB),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette[summary.id.hashCode().mod(palette.size)]),
    )
}
