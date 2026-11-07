plugins {
    alias(libs.plugins.rudwallet.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.rudwallet.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.rudnam.wallet"

    defaultConfig {
        applicationId = "com.rudnam.wallet"
        // Every release needs a higher versionCode: the app offers a build with a
        // higher one as an update. build.sh sets it from version.sh (one above the
        // published APK); the number here is the floor. 1 and 2 were the Bubblewrap
        // (TWA) app; 4 is the first that checks for updates.
        versionCode = (System.getenv("WALLET_VERSION_CODE") ?: "4").toInt()
        versionName = System.getenv("WALLET_VERSION_NAME") ?: "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Japanese and English, nothing else: per-app language lists only these.
        generateLocaleConfig = true
    }

    // Release builds are signed with android.keystore (git-ignored, see README.md);
    // build.sh passes its passwords in from signing-key.txt.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("android.keystore")
            keyAlias = "android"
            storePassword = System.getenv("WALLET_KEYSTORE_PASSWORD")
            keyPassword = System.getenv("WALLET_KEY_PASSWORD")
        }
    }

    buildTypes {
        // A debug build installs beside the real app, as "RudWallet Preview" (src/debug), so it
        // can be tried without uninstalling anything. It talks to the same server.
        debug {
            applicationIdSuffix = ".preview"
        }
        release {
            if (System.getenv("WALLET_KEYSTORE_PASSWORD") != null) signingConfig = signingConfigs.getByName("release")
            // R8: shrinks, optimizes and obfuscates. The libraries bring their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // What CI publishes for a pull request (android/README.md): the release build, so it runs
        // as fast as the real app (a debuggable Compose app is several times slower), installed
        // beside it as "RudWallet Preview" like a debug build, and signed with the debug key.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The preview's name and the rest of src/debug's resources.
    sourceSets.getByName("preview").res.srcDir("src/debug/res")

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:record"))
    implementation(project(":feature:activity"))
    implementation(project(":feature:account"))
    implementation(project(":feature:insights"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:imports"))
    implementation(project(":feature:signin"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)


    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
