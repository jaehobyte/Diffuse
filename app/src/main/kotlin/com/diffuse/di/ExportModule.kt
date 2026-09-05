package com.diffuse.di

import android.content.ContentResolver
import android.content.Context
import com.diffuse.R
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.export.ExportPipeline
import com.diffuse.feature.export.ExportSettingsStore
import com.diffuse.feature.export.Exporter
import com.diffuse.feature.export.ImageStore
import com.diffuse.feature.export.MediaStoreImageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExportModule {

    /** specs/export.md: files land in `Pictures/<AppName>/`. */
    @Provides
    @Singleton
    fun imageStore(
        resolver: ContentResolver,
        @ApplicationContext context: Context,
    ): ImageStore = MediaStoreImageStore(resolver, context.getString(R.string.app_name))

    @Provides
    @Singleton
    fun exporter(renderer: Renderer, store: ImageStore): Exporter =
        Exporter(ExportPipeline(renderer), store)

    @Provides
    @Singleton
    fun exportSettings(@ApplicationContext context: Context): ExportSettingsStore =
        ExportSettingsStore(context)
}
