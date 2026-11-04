// Plugins every module may use, declared once here with their versions
// (gradle/libs.versions.toml) and applied in the modules that need them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Versions Capacitor's own modules read (rootProject.ext).
apply(from = "variables.gradle")

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Android Lint over the native app's own modules (not Capacitor's): ./gradlew lintNative, as CI runs it.
tasks.register("lintNative") {
    group = "verification"
    description = "Runs Android Lint on app, core:* and feature:*."
    val native = subprojects.filter { it.path == ":app" || it.path.startsWith(":core:") || it.path.startsWith(":feature:") }
    // core:model is plain Kotlin, with no Android to lint.
    dependsOn(native.filter { it.path != ":core:model" }.map { "${it.path}:lintDebug" })
}
