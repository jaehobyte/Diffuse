plugins {
    alias(libs.plugins.diffuse.jvm.library)
}

dependencies {
    // DispatcherProvider exposes CoroutineDispatcher, so it is part of the API surface.
    api(libs.kotlinx.coroutines.core)
}
