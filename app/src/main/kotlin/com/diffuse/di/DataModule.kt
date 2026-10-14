package com.diffuse.di

import android.content.Context
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import com.diffuse.core.data.DefaultProjectRepository
import com.diffuse.core.data.ProjectRepository
import com.diffuse.core.data.TopazWorkspace
import com.diffuse.core.data.db.ProjectDao
import com.diffuse.core.data.db.createProjectDao
import com.diffuse.core.data.file.ProjectFiles
import com.diffuse.core.imaging.render.Renderer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun projectDao(@ApplicationContext context: Context): ProjectDao = createProjectDao(context)

    @Provides
    @Singleton
    fun projectFiles(@ApplicationContext context: Context): ProjectFiles =
        ProjectFiles(context.filesDir)

    @Provides
    @Singleton
    fun projectRepository(
        dao: ProjectDao,
        files: ProjectFiles,
        renderer: Renderer,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): ProjectRepository =
        DefaultProjectRepository(dao, files, renderer, dispatchers, logger = logger)

    /** specs/topaz_enhancement.md §5: Topaz files, apart from every project folder. */
    @Provides
    @Singleton
    fun topazWorkspace(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): TopazWorkspace = TopazWorkspace(File(context.filesDir, "topaz_work"), dispatchers)
}
