pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "cnct-android-sdk"

// `cnct` is the SDK itself: plain Kotlin on the JVM, so the same artifact runs in an Android app and
// on a server (where an API key belongs). Android-only pieces — a Keystore-backed token store, the
// sample app — go in their own modules next to it, so the core never needs the Android SDK to build.
include(":cnct")
