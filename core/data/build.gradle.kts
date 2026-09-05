plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.diffuse.core.data"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.imaging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.room.testing)
}
