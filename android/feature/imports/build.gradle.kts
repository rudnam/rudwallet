// Importing records: PayPay, MUFG and BudgetBakers files, and transit IC cards read over NFC (the card page and its import).
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.imports" }

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
