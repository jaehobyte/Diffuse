import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureAndroid(ext: CommonExtension<*, *, *, *, *, *>) {
    ext.apply {
        compileSdk = libs.version("compileSdk").toInt()

        defaultConfig {
            minSdk = libs.version("minSdk").toInt()
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }

        lint {
            abortOnError = true
            checkDependencies = false
            checkReleaseBuilds = false
        }
    }
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

/**
 * JUnit 5 for pure unit tests (specs/testing.md 3), with the vintage engine so
 * Robolectric / Roborazzi JUnit 4 tests run on the same platform.
 */
internal fun Project.configureUnitTests() {
    dependencies {
        add("testImplementation", platform(libs.lib("junit-bom")))
        add("testImplementation", libs.lib("junit-jupiter-api"))
        add("testImplementation", libs.lib("junit-jupiter-params"))
        add("testRuntimeOnly", libs.lib("junit-jupiter-engine"))
        add("testImplementation", libs.lib("junit4"))
        add("testRuntimeOnly", libs.lib("junit-vintage-engine"))
        add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("turbine"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

/**
 * Robolectric downloads its android-all jar from Maven at test runtime, which
 * scripts/check.sh forbids (--offline). Resolve it through Gradle instead and
 * point Robolectric at the resulting directory.
 */
internal fun Project.configureRobolectric() {
    val androidAll = configurations.create("robolectricAndroidAll") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isVisible = false
    }
    dependencies {
        add(androidAll.name, libs.lib("robolectric-android-all"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("androidx-test-core-ktx"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }

    val depsDir = layout.buildDirectory.dir("robolectric-deps")
    val syncDeps = tasks.register<Sync>("syncRobolectricDeps") {
        from(androidAll)
        into(depsDir)
    }

    tasks.withType<Test>().configureEach {
        dependsOn(syncDeps)
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", depsDir.get().asFile.absolutePath)
        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.logging.enabled", "false")
    }
}
