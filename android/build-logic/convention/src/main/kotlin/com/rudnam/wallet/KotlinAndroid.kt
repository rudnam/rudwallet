package com.rudnam.wallet

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** The version catalog (gradle/libs.versions.toml). */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** What every Android module shares: SDK levels and Java 21. */
internal fun Project.configureKotlinAndroid(android: CommonExtension) {
    // AGP 9's CommonExtension has properties, not configuration blocks.
    // The SDK compiled against; newer AndroidX releases require the newest one.
    // targetSdk (AndroidApplicationConventionPlugin) is what changes runtime behaviour.
    android.compileSdk = 37
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
    // Gradle 9 fails a test task that finds no tests. Hilt's generated test sources make a
    // module with no tests of its own look like it has some, so only those modules skip the check.
    if (!file("src/test").exists()) {
        tasks.withType<Test>().configureEach { failOnNoDiscoveredTests.set(false) }
    }
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
