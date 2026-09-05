package com.diffuse.core.data.db

import android.content.Context
import androidx.room.Room

/**
 * Room is an implementation detail of `core:data`, so the builder stays here rather than
 * putting room-runtime on the app's classpath.
 */
fun createProjectDao(context: Context): ProjectDao =
    Room.databaseBuilder(context, ProjectDatabase::class.java, ProjectDatabase.NAME)
        .build()
        .projectDao()
