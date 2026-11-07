// The server's API (internal/server, README.md "API"): requests, answers, and signing them in.
plugins {
    alias(libs.plugins.rudwallet.android.library)
    alias(libs.plugins.rudwallet.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The server the app talks to: rudwallet.server in gradle.properties, or
 * -Prudwallet.server=https://… for another (a local server, say).
 */
val server: String = providers.gradleProperty("rudwallet.server").orNull ?: error("rudwallet.server is missing from gradle.properties")

android {
    namespace = "com.rudnam.wallet.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        buildConfigField("String", "SERVER", "\"${server.trimEnd('/')}\"")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.okhttp.mockwebserver)
}
