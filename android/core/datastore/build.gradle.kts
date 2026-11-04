// What the app keeps on the phone outside the database: the session, and the user's preferences.
plugins {
    alias(libs.plugins.rudwallet.android.library)
    alias(libs.plugins.rudwallet.hilt)
}

android { namespace = "com.rudnam.wallet.core.datastore" }

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
}
