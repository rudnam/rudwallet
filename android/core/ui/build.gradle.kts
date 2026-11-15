// Pieces of screen that know the app's models: money and dates, category tiles, the record list.
plugins {
    alias(libs.plugins.rudwallet.android.library.compose)
}

android { namespace = "com.rudnam.wallet.core.ui" }

dependencies {
    api(project(":core:model"))
    api(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(project(":core:testing"))
}
