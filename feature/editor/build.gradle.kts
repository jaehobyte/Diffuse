plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.compose)
    alias(libs.plugins.diffuse.hilt)
}

android {
    namespace = "com.diffuse.feature.editor"

    // specs/ai_provider.md §6: the shared fakes are a source directory, not testFixtures,
    // which Kotlin has no compilation for under AGP 8.13 (progress.md, T05).
    sourceSets.getByName("test").java.srcDir(rootProject.file("core/ai/src/testShared/kotlin"))
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.imaging)
    implementation(projects.core.data)
    implementation(projects.core.ai)

    // DESIGN.md §7 mandates one rounded icon set; the core Compose set has no
    // Crop/Palette/Tune/Compare. R8 strips the unused vectors — re-check at T20 against
    // the 15MB APK budget (specs/architecture.md §8).
    implementation(libs.compose.material.icons.extended)

    // specs/prompt_input.md §3: RECORD_AUDIO is requested from the composable that owns the
    // mic, so the launcher API has to be visible here.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
