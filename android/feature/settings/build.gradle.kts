// Settings, the fourth tab: the menu, accounts, categories, currency, appearance, language, and the account itself.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.settings" }

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
