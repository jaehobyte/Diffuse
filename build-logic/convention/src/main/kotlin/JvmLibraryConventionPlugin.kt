import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure Kotlin/JVM module. ARCHITECTURE.md 3 requires core:common to carry no
 * Android dependencies, so it cannot use the android-library convention.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        configureKotlin()
        configureUnitTests()

        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-core"))
        }

        // scripts/check.sh invokes `testDebugUnitTest`, an Android-only task name.
        // Alias it so JVM-only modules are still covered by the same verdict.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Alias for `test` so scripts/check.sh covers this JVM-only module."
            dependsOn("test")
        }
    }
}
