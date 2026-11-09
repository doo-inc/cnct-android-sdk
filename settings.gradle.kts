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
// does: `cnct-android` is the Keystore-backed token store, published as a second artifact; `cnct-ui`
// is the ready-made Compose chat screen, a third, built only on the headless client's public API; and
// `sample` is an app that shows it off, which is not published at all.
include(":cnct")
include(":cnct-android")
include(":cnct-ui")
include(":sample")
