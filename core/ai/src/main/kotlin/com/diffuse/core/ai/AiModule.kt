package com.diffuse.core.ai

import com.diffuse.core.ai.sam3.Sam3Client
import com.diffuse.core.ai.sam3.Sam3ConfigSource
import com.diffuse.core.ai.sam3.Sam3SegmentationProvider
import com.diffuse.core.ai.sam3.Sam3Settings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** specs/ai_provider.md §7. Tests replace this with `@TestInstallIn` binding the fakes. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiModule {

    @Binds
    abstract fun segmentation(impl: Sam3SegmentationProvider): SegmentationProvider

    @Binds
    abstract fun config(impl: Sam3Settings): Sam3ConfigSource

    companion object {
        @Provides
        @Singleton
        fun okHttp(): OkHttpClient = Sam3Client.defaultOkHttp()
    }
}
