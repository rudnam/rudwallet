// What tests share: fakes of the repositories, a sample wallet, and a rule for coroutines.
plugins {
    alias(libs.plugins.rudwallet.android.library)
}

android { namespace = "com.rudnam.wallet.core.testing" }

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))
    api(project(":core:datastore"))
    implementation(libs.androidx.datastore.preferences)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
