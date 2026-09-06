plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.diffuse.core.ai"

    // specs/ai_provider.md §6. Kotlin has no testFixtures compilation under AGP 8.13
    // (see progress.md, T05), so the fakes are a source directory that both this
    // module's tests and :feature:editor's tests compile.
    sourceSets.getByName("test").java.srcDir("src/testShared/kotlin")
}

dependencies {
    api(projects.core.common)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Localhost only; CLAUDE.md forbids reaching an external host from a test.
    testImplementation(libs.okhttp.mockwebserver)
}
