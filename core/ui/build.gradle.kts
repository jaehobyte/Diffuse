plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.compose)
}

android {
    namespace = "com.diffuse.core.ui"
}

dependencies {
    implementation(projects.core.common)

    // DESIGN.md §7 mandates one rounded icon set, and the core set has no Mic. The library is
    // already in the APK through :feature:editor, so this costs nothing.
    implementation(libs.compose.material.icons.extended)
}
