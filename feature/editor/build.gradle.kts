plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.compose)
    alias(libs.plugins.diffuse.hilt)
}

android {
    namespace = "com.diffuse.feature.editor"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.imaging)
    implementation(projects.core.data)

    // DESIGN.md §7 mandates one rounded icon set; the core Compose set has no
    // Crop/Palette/Tune/Compare. R8 strips the unused vectors — re-check at T20 against
    // the 15MB APK budget (specs/architecture.md §8).
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
