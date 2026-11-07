// Activity, the second tab: records a month at a time with filters, search through every month,
// what's waiting for a check, and changing many records at once.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.activity" }

dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
