plugins {
    alias(libs.plugins.diffuse.android.library)
    alias(libs.plugins.diffuse.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.diffuse.core.data"
}

// specs/persistence.md: export the schema so v2 has something to migrate from.
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}

dependencies {
    implementation(projects.core.common)
    // ProjectRepository takes SourceImage and returns EditDocument, so core:imaging is
    // part of this module's API surface, not an implementation detail.
    api(projects.core.imaging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.room.testing)
}
