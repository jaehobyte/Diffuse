plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.compose)
    alias(libs.plugins.diffuse.hilt)
}

android {
    namespace = "com.diffuse.feature.browse"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.data)

    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
