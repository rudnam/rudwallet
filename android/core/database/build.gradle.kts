// The phone's copy of the wallet (Room): what the screens show, kept in step with the server by core:data.
plugins {
    alias(libs.plugins.rudwallet.android.library)
    alias(libs.plugins.rudwallet.android.room)
    alias(libs.plugins.rudwallet.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.rudnam.wallet.core.database" }

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
