pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Note: Gradle Plugin Portal is intentionally omitted. Its /m2/
        // endpoint 303-redirects the com.android.application marker to Maven
        // Central, where it 404s. We map plugin IDs to modules directly below.
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
