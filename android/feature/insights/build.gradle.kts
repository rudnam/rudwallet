// Insights, the third tab: spending and income over a week, a month, a year or a range, and the budgets.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.insights" }

dependencies {
    implementation(project(":core:common"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
}
