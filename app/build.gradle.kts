import java.security.MessageDigest
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
val mtaKey: String = localProps.getProperty("MTA_BUSTIME_KEY", "").trim()

// Personal settings (who to contact, emergency number) come from personal.properties (private),
// or from personal.properties.example if you haven't made your own yet.
val personalProps = Properties().apply {
    // -PpersonalFile=some.properties builds with a different settings file (e.g. made-up ones for screenshots).
    val own = rootProject.file((findProperty("personalFile") as String?) ?: "personal.properties")
    val example = rootProject.file("personal.properties.example")
    (if (own.exists()) own else example).takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun personal(name: String, default: String) =
    personalProps.getProperty(name, default).trim().replace("\\", "").replace("\"", "'")

// Kasa smart plugs: the Kasa account login lives in private/kasa-login.properties (never committed).
// Only a one-way hash of it goes into the app: sha256(sha1(email) + sha1(password)), which is what
// the plugs check (KLAP v2). The file is read line by line so any character in the password works.
val kasaAuthHash: String = run {
    val f = rootProject.file("private/kasa-login.properties")
    if (!f.exists()) return@run ""
    val kv = f.readLines(Charsets.UTF_8).map { it.removePrefix("﻿") }
        .filter { "=" in it && !it.trimStart().startsWith("#") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    val email = kv["KASA_EMAIL"].orEmpty(); val password = kv["KASA_PASSWORD"].orEmpty()
    if (email.isEmpty() || password.isEmpty()) return@run ""
    fun h(alg: String, b: ByteArray): ByteArray = MessageDigest.getInstance(alg).digest(b)
    val digest: ByteArray = h("SHA-256", h("SHA-1", email.toByteArray()) + h("SHA-1", password.toByteArray()))
    digest.joinToString("") { b: Byte -> String.format("%02x", b) }
}

android {
    namespace = "app.moyna"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.moyna"
        minSdk = 30   // Android 11, the phone's version
        targetSdk = 35
        versionCode = 20
        versionName = "2.0"

        // The phone only runs 32-bit apps. Ship only 32-bit ARM native code.
        ndk { abiFilters += listOf("armeabi-v7a") }

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "CARE_CONTACT", "\"${personal("CARE_CONTACT", "a family member")}\"")
        buildConfigField("String", "MTA_BUSTIME_KEY", "\"$mtaKey\"")
        buildConfigField("String", "BUS_STOPS", "\"${personal("BUS_STOPS", "")}\"")
        buildConfigField("String", "HOME_LAT", "\"${personal("HOME_LAT", "")}\"")
        buildConfigField("String", "HOME_LON", "\"${personal("HOME_LON", "")}\"")
        buildConfigField("String", "ADMIN_PIN", "\"${personal("ADMIN_PIN", "")}\"")
        buildConfigField("String", "EMERGENCY_NUMBER", "\"${personal("EMERGENCY_NUMBER", "911")}\"")
        buildConfigField("String", "KASA_AUTH_HASH", "\"$kasaAuthHash\"")
        buildConfigField("String", "LIGHTS", "\"${personal("LIGHTS", "")}\"")
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

    // Private files (prayer calendar, azan recording) are bundled from private/ at build time.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("privateAssets"))
}

// Copy the prayer calendar CSVs and the azan recording from private/ (git-ignored, never committed)
// into the app. If the folders don't exist the app still builds; prayer times are then calculated.
val copyPrivateAssets = tasks.register<Copy>("copyPrivateAssets") {
    from(rootProject.file("private/prayer-times")) { include("*.csv"); into("prayer") }
    from(rootProject.file("private/azan")) { include("*.ogg", "*.mp3"); into("azan") }
    from(rootProject.file("private/duas")) { include("*.json"); into("duas") }   // dua of the day (Tanzil text)
    into(layout.buildDirectory.dir("privateAssets"))
}
tasks.named("preBuild") { dependsOn(copyPrivateAssets) }

dependencies {
    // Websocket connection to Gemini. Pure Java/Kotlin, no native code, so 32-bit is fine.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Prayer time calculation for months with no calendar file. Pure Java, so 32-bit is fine.
    implementation("com.batoulapps.adhan:adhan:1.2.1")
}
