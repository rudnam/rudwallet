// Repositories: what the screens ask for. They read the phone's database and keep it in step with the server.
plugins {
    alias(libs.plugins.rudwallet.android.library)
    alias(libs.plugins.rudwallet.hilt)
}

android { namespace = "com.rudnam.wallet.core.data" }

dependencies {
    api(project(":core:model"))
    api(project(":core:datastore"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.runtime)
}
