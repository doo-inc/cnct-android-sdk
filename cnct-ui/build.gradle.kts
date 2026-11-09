import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    `maven-publish`
}

android {
    namespace = "ooo.doo.cnct.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // The SDK's timestamps are java.time.Instant, which Android only has from API 26. The library
        // desugars its own use of it; an app below 26 needs the same line (see the README).
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":cnct"))
    api(project(":cnct-android"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // About 60 KB, and the difference between a camera photo sent upright and one sent sideways on the
    // phones (API 21–27) that ImageDecoder does not cover. No image-loading library: the authenticated
    // bytes are decoded with sampling, at the size they are drawn.
    implementation(libs.androidx.exifinterface)

    coreLibraryDesugaring(libs.desugar)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.manifest)
}

/**
 * The interface's words come from one table shared by the web, iOS and Android SDKs,
 * `docs/chat-ui/strings.json`. This writes it out as Android resources — `cnct_` + snake_case
 * names, `{name}` as positional `%1$s` arguments in the order the English uses them — and as the
 * Kotlin table that maps each resource back to its key, for the `strings` overrides. Run it after
 * the JSON changes; `StringTableTest` fails until you do.
 */
abstract class GenerateCnctStrings : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val table: RegularFileProperty

    @get:OutputFile
    abstract val english: RegularFileProperty

    @get:OutputFile
    abstract val arabic: RegularFileProperty

    @get:OutputFile
    abstract val kotlinTable: RegularFileProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val languages = groovy.json.JsonSlurper().parse(table.get().asFile) as Map<String, Map<String, String>>
        val en = languages.getValue("en")
        val ar = languages.getValue("ar")
        val placeholder = Regex("""\{(\w+)\}""")
        fun resourceName(key: String) = "cnct_" + Regex("([a-z0-9])([A-Z])").replace(key, "$1_$2").lowercase()
        fun argsOf(key: String) = placeholder.findAll(en.getValue(key)).map { it.groupValues[1] }.distinct().toList()
        fun escape(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\" + escaped else escaped
        }
        fun xml(values: Map<String, String>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<!-- Generated from docs/chat-ui/strings.json by ./gradlew :cnct-ui:generateCnctStrings. Do not edit. -->\n")
            append("<resources>\n")
            for (key in en.keys) {
                val raw = values[key] ?: continue
                val args = argsOf(key)
                val positional = placeholder.replace(raw) { match ->
                    val index = args.indexOf(match.groupValues[1])
                    if (index < 0) match.value else "%${index + 1}\$s"
                }
                append("    <string name=\"").append(resourceName(key)).append("\">").append(escape(positional)).append("</string>\n")
            }
            append("</resources>\n")
        }
        english.get().asFile.writeText(xml(en))
        arabic.get().asFile.writeText(xml(ar))
        kotlinTable.get().asFile.writeText(
            buildString {
                append("// Generated from docs/chat-ui/strings.json by ./gradlew :cnct-ui:generateCnctStrings. Do not edit.\n")
                append("package ooo.doo.cnct.ui\n\n")
                append("/** Every key in the shared table, with its resource and its `{placeholders}` in argument order. */\n")
                append("internal object CnctStringTable {\n")
                append("    val entries: List<CnctStringEntry> = listOf(\n")
                for (key in en.keys) {
                    val args = argsOf(key)
                    append("        CnctStringEntry(\"").append(key).append("\", R.string.").append(resourceName(key))
                    if (args.isNotEmpty()) append(", listOf(").append(args.joinToString { "\"$it\"" }).append(")")
                    append("),\n")
                }
                append("    )\n}\n")
            },
        )
    }
}

tasks.register<GenerateCnctStrings>("generateCnctStrings") {
    description = "Writes the chat UI's string resources from docs/chat-ui/strings.json."
    table.set(rootProject.layout.projectDirectory.file("docs/chat-ui/strings.json"))
    english.set(layout.projectDirectory.file("src/main/res/values/strings.xml"))
    arabic.set(layout.projectDirectory.file("src/main/res/values-ar/strings.xml"))
    kotlinTable.set(layout.projectDirectory.file("src/main/kotlin/ooo/doo/cnct/ui/CnctStringTable.kt"))
}

// The variant's component only exists once AGP has configured the project.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "cnct-ui"
                pom {
                    name.set("CNCT for Android — chat UI")
                    description.set(
                        "A ready-made, brandable CNCT chat screen in Jetpack Compose: English and Arabic, " +
                            "right-to-left, photos and files both ways. Built on the headless cnct client.",
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
