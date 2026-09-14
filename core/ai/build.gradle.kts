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

// scripts/install.sh passes `-Pdiffuse.localCreds` so that a build going straight onto a device
// picks the working addresses out of `.env` instead of asking for them again in the 서버 설정
// sheet. Without the flag — which is every ordinary build, including the `assembleDebug` that
// produces a published APK — this stays empty and the defaults below fall back to
// local.properties, so nothing ships. `.env` is git-ignored.
val envProperties = Properties().apply {
    if (!providers.gradleProperty("diffuse.localCreds").isPresent) return@apply
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** `.env` when the flag is on, local.properties otherwise, and empty when neither names it. */
fun serverDefault(envKey: String, localKey: String): String =
    envProperties.getProperty(envKey) ?: localProperties.getProperty(localKey, "")

android {
    namespace = "com.diffuse.core.ai"

    // gradle.properties turns buildConfig off globally; this module is the exception.
    buildFeatures.buildConfig = true

    defaultConfig {
        // specs/testing.md §3 keeps instrumentation out of `scripts/check.sh`; this exists so the
        // SR1-B detector harness can be run against a real device by hand (see
        // scripts/retouch/README.md). `connectedDebugAndroidTest` is never part of green.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SAM3_BASE_URL",
            "\"${serverDefault("SAM3_BASE_URL", "sam3.baseUrl")}\"",
        )
        buildConfigField(
            "String",
            "SAM3_TOKEN",
            "\"${serverDefault("SAM3_AUTH_TOKEN", "sam3.token")}\"",
        )
        // specs/auto_enhance.md §4: blank by default, exactly as SAM 3's is. No address ships.
        buildConfigField(
            "String",
            "MONET_BASE_URL",
            "\"${serverDefault("MONET_BASE_URL", "monet.baseUrl")}\"",
        )
        buildConfigField(
            "String",
            "MONET_TOKEN",
            "\"${serverDefault("MONET_AUTH_TOKEN", "monet.token")}\"",
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
    // work/decisions.md T79: the on-device portrait gate. Play-services delivery, so the
    // model is downloaded by Play rather than bundled into the APK.
    implementation(libs.play.services.mlkit.face.detection)

    // specs/skin_retouch_pipeline.md §1.1 and work/tasks.md requirement 8: the ONNX runtime is
    // here to **measure** the acne detector port on a device, not because a runtime has been
    // adopted. Debug only, so no release variant and no release APK contains it, and nothing in
    // AiModule binds the adapter that uses it.
    debugImplementation(libs.onnxruntime.android)

    // Localhost only; CLAUDE.md forbids reaching an external host from a test.
    testImplementation(libs.okhttp.mockwebserver)

    // On-device harness only. Not run by scripts/check.sh (specs/testing.md §3).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
