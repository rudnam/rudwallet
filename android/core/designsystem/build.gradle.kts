// The app's look on Material 3: colours, type (Inter), shapes, line icons and shared components.
plugins {
    alias(libs.plugins.rudwallet.android.library.compose)
}

android { namespace = "com.rudnam.wallet.core.designsystem" }

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
}
