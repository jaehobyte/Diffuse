pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "diffuse"

include(":app")
include(":core:common")
include(":core:imaging")
include(":core:ui")
include(":core:data")
include(":feature:browse")
include(":feature:editor")
include(":feature:export")
