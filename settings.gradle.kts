pluginManagement {
    repositories {
        // Note: google() replaced with explicit maven + POM-only metadata
        // sources as a diagnostic: CI Gradle fails to resolve the AGP
        // .module metadata even though curl fetches it fine (HTTP 200).
        // If POM-only resolution works, the .module was the problem.
        // Note: Gradle Plugin Portal is intentionally omitted. Its /m2/
        // endpoint 303-redirects plugin markers to Maven Central
        // (https://repo.maven.apache.org/maven2/...), where the
        // com.android.application marker 404s. We map plugin IDs to modules
        // directly below instead.
        maven("https://dl.google.com/dl/android/maven2/") {
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application" ->
                    useModule("com.android.tools.build:gradle:8.5.2")
                "org.jetbrains.kotlin.android" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
                "com.google.devtools.ksp" ->
                    useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:1.9.24-1.0.20")
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-serialization:1.9.24")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "conversation-archive"
include(":app")
