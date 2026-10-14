package com.diffuse.di

import android.content.ContentResolver
import android.content.Context
import com.diffuse.R
import com.diffuse.core.imaging.render.Renderer
import com.diffuse.feature.export.ExportPipeline
import com.diffuse.feature.export.ExportSettingsStore
import com.diffuse.feature.export.Exporter
import com.diffuse.feature.export.ImageStore
import com.diffuse.feature.export.MediaStoreFileStore
import com.diffuse.feature.export.MediaStoreImageStore
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import com.diffuse.feature.editor.tools.enhance.ResultFileSaver
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
        dispatchers: DispatcherProvider,
    ): ImageStore = MediaStoreImageStore(resolver, context.getString(R.string.app_name), dispatchers)

    @Provides
    @Singleton
    fun exporter(renderer: Renderer, store: ImageStore): Exporter =
        Exporter(ExportPipeline(renderer), store)

    /**
     * specs/export.md §고해상도 결과 저장: the editor's Topaz result goes to the same album, byte
     * for byte. The editor sees only `ResultFileSaver` (architecture.md §4.1).
     */
    @Provides
    @Singleton
    fun resultFileSaver(
        resolver: ContentResolver,
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): ResultFileSaver {
        val store = MediaStoreFileStore(resolver, context.getString(R.string.app_name), dispatchers)
        return ResultFileSaver { file, mimeType, width, height ->
            when (val copied = store.copy(file, mimeType, width, height)) {
                is Result.Success -> Result.Success(Unit)
                is Result.Failure -> copied
            }
        }
    }

    @Provides
    @Singleton
    fun exportSettings(@ApplicationContext context: Context): ExportSettingsStore =
        ExportSettingsStore(context)
}
