package com.rudnam.wallet

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Compose, from the BOM, with previews in debug builds and Robolectric screenshot tests. */
internal fun Project.configureCompose(android: CommonExtension<*, *, *, *, *, *>) {
    android.buildFeatures.compose = true
    pluginManager.apply("io.github.takahirom.roborazzi")
    dependencies {
        val bom = libs.findLibrary("androidx-compose-bom").get()
        add("implementation", platform(bom))
        add("testImplementation", platform(bom))
        add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        add("testImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("roborazzi").get())
        add("testImplementation", libs.findLibrary("roborazzi-compose").get())
    }
}
