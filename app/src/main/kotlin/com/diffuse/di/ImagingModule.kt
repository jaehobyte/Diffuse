package com.diffuse.di

import android.content.ContentResolver
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.imaging.load.ImageLoader
import com.diffuse.core.imaging.render.CpuRenderer
import com.diffuse.core.imaging.render.Renderer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** specs/architecture.md §4.2 keeps `core:imaging` free of Hilt, so it is wired here. */
@Module
@InstallIn(SingletonComponent::class)
object ImagingModule {

    @Provides
    @Singleton
    fun imageLoader(
        resolver: ContentResolver,
        dispatchers: DispatcherProvider,
    ): ImageLoader = ImageLoader(resolver, dispatchers)

    @Provides
    @Singleton
    fun renderer(loader: ImageLoader, dispatchers: DispatcherProvider): Renderer =
        CpuRenderer(loader, dispatchers)
}
