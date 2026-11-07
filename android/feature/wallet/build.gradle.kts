// Wallet, the first tab: the total, the accounts as a stack of cards, this month, the latest records.
plugins {
    alias(libs.plugins.rudwallet.android.feature)
}

android { namespace = "com.rudnam.wallet.feature.wallet" }

dependencies {
    implementation(project(":core:common"))
}
