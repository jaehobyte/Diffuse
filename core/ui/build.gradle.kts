plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.compose)
}

android {
    namespace = "com.diffuse.core.ui"
}

dependencies {
    implementation(projects.core.common)
}
