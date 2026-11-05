import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "ooo.doo.cnct.android"
    compileSdk = 35

    defaultConfig {
        // The SDK's own floor. Below 23 there is no AES key in the Android Keystore, and the token
        // store says what it does about that rather than pretending — see CnctKeystoreTokenStore.
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Robolectric reads the merged manifest and resources AGP prepares for unit tests.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // The same promises as the core: every public declaration says it is public, and a warning is an
    // error.
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":cnct"))
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// The variant's component only exists once AGP has configured the project.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "cnct-android"
                pom {
                    name.set("CNCT for Android — Android pieces")
                    description.set(
                        "What the CNCT SDK needs the Android SDK for: a chat session token store that " +
                            "encrypts with an Android Keystore key.",
                    )
                    url.set("https://github.com/doo-inc/cnct-android-sdk")
                    licenses {
                        license {
                            name.set("BSD-3-Clause")
                            url.set("https://opensource.org/licenses/BSD-3-Clause")
                        }
                    }
                    scm {
                        url.set("https://github.com/doo-inc/cnct-android-sdk")
                        connection.set("scm:git:https://github.com/doo-inc/cnct-android-sdk.git")
                    }
                    developers {
                        developer {
                            organization.set("DOO Inc")
                            organizationUrl.set("https://doo.ooo")
                        }
                    }
                }
            }
        }
    }
}
