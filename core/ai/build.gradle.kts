import java.util.Properties

plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.diffuse.hilt)
}

// specs/segmentation.md §6. Build-time defaults for the SAM 3 service. They live here rather
// than in :app because :app depends on :core:ai, not the other way round, and BuildConfig is
// per-module. Absent keys give empty strings, which is the "not configured" state.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.diffuse.core.ai"

    // gradle.properties turns buildConfig off globally; this module is the exception.
    buildFeatures.buildConfig = true

    defaultConfig {
        buildConfigField(
            "String",
            "SAM3_BASE_URL",
            "\"${localProperties.getProperty("sam3.baseUrl", "")}\"",
        )
        buildConfigField(
            "String",
            "SAM3_TOKEN",
            "\"${localProperties.getProperty("sam3.token", "")}\"",
        )
    }

    // specs/ai_provider.md §6. Kotlin has no testFixtures compilation under AGP 8.13
    // (see progress.md, T05), so the fakes are a source directory that both this
    // module's tests and :feature:editor's tests compile.
    sourceSets.getByName("test").java.srcDir("src/testShared/kotlin")
}

dependencies {
    api(projects.core.common)
    // specs/ai_provider.md §2: for `AdjustKind` alone, which `PlanStep.Adjust` carries.
    implementation(projects.core.imaging)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Localhost only; CLAUDE.md forbids reaching an external host from a test.
    testImplementation(libs.okhttp.mockwebserver)
}
