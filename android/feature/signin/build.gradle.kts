// Signing in: email and password, a new account, a forgotten password, Google (in the phone's browser), and the demo.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.signin" }

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
