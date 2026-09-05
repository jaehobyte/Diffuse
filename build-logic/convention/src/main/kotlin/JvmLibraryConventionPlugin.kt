import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * Pure Kotlin/JVM module. specs/architecture.md 3 requires core:common to carry no
 * Android dependencies, so it cannot use the android-library convention.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            // java-library so the module can expose transitive API (`api`), which a plain
            // `java` plugin does not provide.
            apply("java-library")
            apply("org.jetbrains.kotlin.jvm")
        }

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        configureKotlin()
        configureUnitTests()

        // scripts/check.sh invokes `testDebugUnitTest`, an Android-only task name.
        // Alias it so JVM-only modules are still covered by the same verdict.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Alias for `test` so scripts/check.sh covers this JVM-only module."
            dependsOn("test")
        }
    }
}
