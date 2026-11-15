plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Keep build output out of the project folder, so cloud-synced folders (Dropbox, iCloud, etc.)
// don't have to sync thousands of temporary files. The APK ends up in
// <LOCALAPPDATA or the temp folder>/moyna-build/app/outputs/apk/debug/app-debug.apk
val buildOutside = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "moyna-build")
allprojects {
    layout.buildDirectory.set(File(buildOutside, project.name))
}
