plugins {
    alias(libs.plugins.diffuse.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.diffuse.compose)
    alias(libs.plugins.diffuse.hilt)
}

android {
    namespace = "com.diffuse"

    defaultConfig {
        applicationId = "com.diffuse"
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.imaging)
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.feature.browse)
    implementation(projects.feature.editor)
    implementation(projects.feature.export)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
}

dependencies {
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
