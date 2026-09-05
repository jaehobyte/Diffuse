import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
            apply("de.mannodermaus.android-junit5")
        }

        extensions.configure<LibraryExtension> {
            configureAndroid(this)
        }

        configureKotlin()
        configureUnitTests()
        configureRobolectric()

        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-core"))
        }
    }
}
