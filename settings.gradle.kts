pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "cnct-android-sdk"

// `cnct` is the SDK itself: plain Kotlin on the JVM, so the same artifact runs in an Android app and
// on a server (where an API key belongs). What needs the Android SDK sits beside it, so the core never
// does: `cnct-android` is the Keystore-backed token store, published as a second artifact, and
// `sample` is a Compose chat screen built on the headless client, which is not published at all.
include(":cnct")
include(":cnct-android")
include(":sample")
