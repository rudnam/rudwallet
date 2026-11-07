// The record sheet: adding a record, changing one, confirming one to review, deleting one.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.record" }

dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
