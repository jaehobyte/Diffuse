package com.diffuse.di

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.diffuse.core.common.DefaultDispatcherProvider
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * specs/architecture.md §3 keeps `core:common` free of Android, so its bindings live here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    @Singleton
    fun logger(): Logger = AndroidLogger

    @Provides
    fun contentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver
}

/** specs/architecture.md §9: the one place `android.util.Log` is called. */
private object AndroidLogger : Logger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun warn(tag: String, message: String, cause: Throwable?) {
        Log.w(tag, message, cause)
    }

    override fun error(tag: String, message: String, cause: Throwable?) {
        Log.e(tag, message, cause)
    }
}
