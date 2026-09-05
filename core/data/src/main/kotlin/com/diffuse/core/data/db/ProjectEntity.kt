package com.diffuse.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** specs/persistence.md. `width`/`height` are post-crop, so Browse can lay out a masonry. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "thumbPath") val thumbPath: String,
)
