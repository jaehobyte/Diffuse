import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.detekt)
}

// One root-level `detekt` task over every module, which is what scripts/check.sh invokes.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        subprojects.flatMap { p ->
            listOf(
                "${p.projectDir}/src/main/kotlin",
                "${p.projectDir}/src/test/kotlin",
            )
        },
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}

// ---------------------------------------------------------------------------
// dependencyGuard - enforces the module graph in specs/architecture.md 3 and the
// dependency rules in 4. Referenced by specs/architecture.md 4 ("T01 sets it up")
// and invoked by scripts/check.sh. Frozen: the Ralph loop may not edit this.
// ---------------------------------------------------------------------------
val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    ":core:common" to emptySet(),
    ":core:imaging" to setOf(":core:common"),
    ":core:ai" to setOf(":core:common", ":core:imaging"),
    ":core:ui" to setOf(":core:common"),
    ":core:data" to setOf(":core:common", ":core:imaging"),
    ":feature:browse" to setOf(":core:common", ":core:ui", ":core:data"),
    ":feature:editor" to setOf(":core:common", ":core:ui", ":core:imaging", ":core:data", ":core:ai"),
    ":feature:export" to setOf(":core:common", ":core:ui", ":core:imaging"),
    ":app" to setOf(
        ":core:common", ":core:imaging", ":core:ai", ":core:ui", ":core:data",
        ":feature:browse", ":feature:editor", ":feature:export",
    ),
)

// specs/architecture.md 4.2: core:imaging is plain Kotlin + android.graphics.
val forbiddenGroupPrefixes: Map<String, Set<String>> = mapOf(
    ":core:imaging" to setOf("androidx.compose", "com.google.dagger", "androidx.hilt", "androidx.room"),
    // 4.3: core:ui knows nothing about documents or rendering.
    ":core:ui" to setOf("androidx.room"),
    // ai_provider.md 2: core:ai is a model boundary, not UI and not storage.
    ":core:ai" to setOf("androidx.compose", "androidx.room"),
)

val guardedConfigurations = setOf(
    "api", "implementation", "compileOnly", "runtimeOnly",
    "debugImplementation", "releaseImplementation", "debugApi", "releaseApi",
)

tasks.register("dependencyGuard") {
    group = "verification"
    description = "Fails the build if the module graph violates specs/architecture.md 3/4."
    notCompatibleWithConfigurationCache("Reads other projects' configurations at execution time.")
    doLast {
        val violations = mutableListOf<String>()

        subprojects
            .filter { it.buildFile.exists() }
            .forEach { p ->
                val allowed = allowedProjectDeps[p.path]
                if (allowed == null) {
                    violations += "${p.path}: not listed in specs/architecture.md 3. " +
                        "A new core module needs an ADR (rule 4.5)."
                    return@forEach
                }

                val guarded = p.configurations.filter { it.name in guardedConfigurations }

                guarded.flatMap { it.dependencies }
                    .filterIsInstance<ProjectDependency>()
                    .map { it.path }
                    .toSet()
                    .forEach { dep ->
                        when {
                            dep == ":app" ->
                                violations += "${p.path} -> :app violates rule 4.4 (nothing depends on app)."
                            dep.startsWith(":feature:") && p.path.startsWith(":feature:") ->
                                violations += "${p.path} -> $dep violates rule 4.1 " +
                                    "(feature never depends on feature; go through app navigation)."
                            dep !in allowed ->
                                violations += "${p.path} -> $dep is not in the specs/architecture.md 3 module map."
                        }
                    }

                forbiddenGroupPrefixes[p.path].orEmpty().forEach { prefix ->
                    val hits = guarded.flatMap { it.dependencies }
                        .filterIsInstance<ExternalModuleDependency>()
                        .filter { it.group.orEmpty().startsWith(prefix) }
                        .map { "${it.group}:${it.name}" }
                        .distinct()
                    if (hits.isNotEmpty()) {
                        violations += "${p.path} must not depend on '$prefix' " +
                            "(specs/architecture.md 4): ${hits.joinToString()}"
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "dependencyGuard found ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
