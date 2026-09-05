package com.diffuse.core.data

import com.diffuse.core.common.Result
import com.diffuse.core.imaging.load.SourceImage
import com.diffuse.core.imaging.model.EditDocument
import kotlinx.coroutines.flow.Flow

/** specs/persistence.md. A project is a folder plus one Room row. */
data class ProjectSummary(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val widthPx: Int,
    val heightPx: Int,
    val thumbPath: String,
)

interface ProjectRepository {
    /** Newest `updatedAt` first. */
    fun observeAll(): Flow<List<ProjectSummary>>
    suspend fun create(source: SourceImage): Result<String>
    suspend fun load(id: String): Result<EditDocument>
    suspend fun save(document: EditDocument): Result<Unit>
    suspend fun duplicate(id: String): Result<String>
    suspend fun delete(id: String): Result<Unit>
}
