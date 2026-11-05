import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    `maven-publish`
}

// Java 11 bytecode: what current Android Gradle Plugin and Flutter templates compile against, and
// any JVM server from the last six years runs it.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    // Every public declaration says it is public. An SDK that leaks an internal helper into its API
    // has promised to keep it.
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("cnct.version", project.version.toString())
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "cnct"
            pom {
                name.set("CNCT for Android")
                description.set(
                    "The official CNCT SDK for Android and the JVM — live chat, bookings and tickets " +
                        "against a CNCT workspace, using the credentials CNCT issues you.",
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
