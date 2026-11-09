import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Where the sample points comes from `local.properties`, which git ignores, or `-P` on the command
// line — never from the source. The public key is public, but it is yours, and a sample that ships
// with somebody's inbox in it is a sample that posts into their queue.
//
//   cnct.publicKey=your-inbox-public-key
//   cnct.baseUrl=http://10.0.2.2:3001     # optional; without it, https://app.doo.ooo
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(StringReader(it)) }
}

fun setting(name: String): String =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: ""

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "ooo.doo.cnct.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "ooo.doo.cnct.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "CNCT_PUBLIC_KEY", quoted(setting("cnct.publicKey")))
        buildConfigField("String", "CNCT_BASE_URL", quoted(setting("cnct.baseUrl")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // The SDK's timestamps are java.time.Instant, which Android only has from API 26. Desugaring
        // gives it to every version the sample runs on — any app below 26 needs the same line.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // The chat UI, which brings the headless client and the Keystore token store with it.
    implementation(project(":cnct-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    coreLibraryDesugaring(libs.desugar)
}
