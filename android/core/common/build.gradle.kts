// Small things every layer shares: coroutine dispatchers and the app-wide scope.
plugins {
    alias(libs.plugins.rudwallet.android.library)
    alias(libs.plugins.rudwallet.hilt)
}

android { namespace = "com.rudnam.wallet.core.common" }

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
