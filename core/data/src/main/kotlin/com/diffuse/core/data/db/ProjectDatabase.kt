package com.diffuse.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** specs/persistence.md: v1 has no migrations; the schema is exported so v2 can add them. */
@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        const val NAME = "projects.db"
    }
}
