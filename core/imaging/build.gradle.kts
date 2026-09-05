plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.diffuse.core.imaging"
}

dependencies {
    api(projects.core.common)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)
}
