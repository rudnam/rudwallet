// One account, opened from its card on Wallet: the card, this month in and out, its records, and Count cash.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.account" }

dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
