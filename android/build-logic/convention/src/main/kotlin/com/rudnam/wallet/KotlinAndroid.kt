package com.rudnam.wallet

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** The version catalog (gradle/libs.versions.toml). */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** What every Android module shares: SDK levels and Java 21. */
internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    // AGP 9's CommonExtension has properties, not configuration blocks.
    android.compileSdk = 36
    android.defaultConfig.minSdk = 24
    android.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // java.time and friends on Android 7 (minSdk 24).
        isCoreLibraryDesugaringEnabled = true
    }
    android.lint.apply {
        abortOnError = true
        // Versions are pinned on purpose (gradle/libs.versions.toml says why); updating them is a change of its own.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    // Robolectric draws screens and reads resources on the JVM.
    android.testOptions.unitTests.isIncludeAndroidResources = true
    dependencies.add("coreLibraryDesugaring", libs.findLibrary("android-desugarJdkLibs").get())
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false))
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }
}
