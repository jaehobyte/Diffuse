package com.diffuse.core.ai

import com.diffuse.core.ai.gemini.GeminiConfigSource
import com.diffuse.core.ai.gemini.GeminiEraseClient
import com.diffuse.core.ai.gemini.GeminiEraseProvider
import com.diffuse.core.ai.gemini.GeminiFillProvider
import com.diffuse.core.ai.gemini.GeminiPlanClient
import com.diffuse.core.ai.gemini.GeminiPlanProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Client
import com.diffuse.core.ai.sam3.Sam3ConfigSource
import com.diffuse.core.ai.sam3.Sam3SegmentationProvider
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.AndroidSpeechInput
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.common.DispatcherProvider
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

    @Binds
    abstract fun speech(impl: AndroidSpeechInput): SpeechInput

    @Binds
    abstract fun geminiConfig(impl: GeminiSettings): GeminiConfigSource

    @Binds
    abstract fun erase(impl: GeminiEraseProvider): EraseProvider

    @Binds
    abstract fun fill(impl: GeminiFillProvider): FillProvider

    @Binds
    abstract fun plan(impl: GeminiPlanProvider): EditPlanProvider

    companion object {
        @Provides
        @Singleton
        fun okHttp(): OkHttpClient = Sam3Client.defaultOkHttp()

        /**
         * Provided rather than `@Inject`-constructed: `Sam3Client` is internal to this module,
         * and keeping it out of the graph's public surface means no feature can reach past
         * `SegmentationProvider` to the wire.
         */
        @Provides
        @Singleton
        fun sam3Client(
            config: Sam3ConfigSource,
            dispatchers: DispatcherProvider,
            okHttp: OkHttpClient,
            logger: com.diffuse.core.common.Logger,
        ): Sam3Client = Sam3Client(config, dispatchers, okHttp, logger)

        /** Provided for the same reason `Sam3Client` is: the wire stays inside this module. */
        @Provides
        @Singleton
        fun geminiEraseClient(
            config: GeminiConfigSource,
            dispatchers: DispatcherProvider,
            okHttp: OkHttpClient,
            logger: com.diffuse.core.common.Logger,
        ): GeminiEraseClient = GeminiEraseClient(config, dispatchers, okHttp, logger)

        /** Provided for the same reason the other two clients are. */
        @Provides
        @Singleton
        fun geminiPlanClient(
            config: GeminiConfigSource,
            dispatchers: DispatcherProvider,
            okHttp: OkHttpClient,
            logger: com.diffuse.core.common.Logger,
        ): GeminiPlanClient = GeminiPlanClient(config, dispatchers, okHttp, logger)
    }
}
