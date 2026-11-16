import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Gemini API key is read from local.properties (private, never shared) at build time.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val geminiKey: String = localProps.getProperty("GEMINI_API_KEY", "").trim()

// Personal settings (who to contact, emergency number) come from personal.properties (private),
// or from personal.properties.example if you haven't made your own yet.
val personalProps = Properties().apply {
    val own = rootProject.file("personal.properties")
    val example = rootProject.file("personal.properties.example")
    (if (own.exists()) own else example).takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun personal(name: String, default: String) =
    personalProps.getProperty(name, default).trim().replace("\\", "").replace("\"", "'")

android {
    namespace = "app.moyna"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.moyna"
        minSdk = 30   // Android 11, the phone's version
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"

        // The phone only runs 32-bit apps. Ship only 32-bit ARM native code.
        ndk { abiFilters += listOf("armeabi-v7a") }

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "CARE_CONTACT", "\"${personal("CARE_CONTACT", "a family member")}\"")
        buildConfigField("String", "ADMIN_PIN", "\"${personal("ADMIN_PIN", "")}\"")
        buildConfigField("String", "EMERGENCY_NUMBER", "\"${personal("EMERGENCY_NUMBER", "911")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Websocket connection to Gemini. Pure Java/Kotlin, no native code, so 32-bit is fine.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
