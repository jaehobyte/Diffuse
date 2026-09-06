import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
            apply("de.mannodermaus.android-junit5")
        }

        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig {
                targetSdk = libs.version("targetSdk").toInt()
                // Bumped per release. v0.2.0 shipped an APK that still reported 0.1.0 because
                // only the asset filename was changed by hand; the installed build should say
                // which one it is.
                versionCode = 8
                versionName = "0.3.5"
            }
            buildTypes {
                getByName("release") {
                    isMinifyEnabled = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }
        }

        configureKotlin()
        configureUnitTests()
        configureRobolectric()

        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-android"))
        }
    }
}
