import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Compose + Roborazzi screenshot testing (specs/testing.md 5).
 * Apply on top of diffuse.android.library or diffuse.android.application.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.plugin.compose")
            apply("io.github.takahirom.roborazzi")
        }

        val android = extensions.findByType(CommonExtension::class.java)
            ?: error("diffuse.compose requires an Android plugin to be applied first")
        android.buildFeatures.compose = true

        // specs/testing.md §5. Roborazzi's Gradle extension has no changeThreshold knob,
        // so the value lives here and ScreenshotOptions is its single reader.
        //
        // The goldens must also be declared as task inputs. Without this Gradle sees no
        // input change, marks the test task UP-TO-DATE and skips it, so verifyRoborazzi
        // passes against a deleted or edited golden -- exactly what testing.md §5 forbids
        // ("a missing golden is a failure, not an auto-record").
        // specs/testing.md §5 requires one shared screenshot configuration. Kotlin has no
        // testFixtures compilation under AGP, so the helper is a source directory every
        // Compose module's unit tests compile, rather than a published artifact.
        android.sourceSets.getByName("test").java
            .srcDir(rootProject.file("core/ui/src/testShared/kotlin"))

        val goldenImages = fileTree("src/test/screenshots") { include("**/*.png") }
        tasks.withType<Test>().configureEach {
            systemProperty("diffuse.roborazzi.changeThreshold", "0.01")
            inputs.files(goldenImages)
                .withPropertyName("roborazziGoldenImages")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        dependencies {
            val bom = platform(libs.lib("compose-bom"))
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("testImplementation", bom)

            add("implementation", libs.lib("compose-ui"))
            add("implementation", libs.lib("compose-ui-graphics"))
            add("implementation", libs.lib("compose-ui-tooling-preview"))
            add("implementation", libs.lib("compose-foundation"))
            add("implementation", libs.lib("compose-material3"))
            add("debugImplementation", libs.lib("compose-ui-tooling"))

            add("testImplementation", libs.lib("compose-ui-test-junit4"))
            add("debugImplementation", libs.lib("compose-ui-test-manifest"))
            add("testImplementation", libs.lib("roborazzi"))
            add("testImplementation", libs.lib("roborazzi-compose"))
            add("testImplementation", libs.lib("roborazzi-junit-rule"))
        }
    }
}
