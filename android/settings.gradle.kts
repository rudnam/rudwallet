pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "RudWallet"

include(":app")

// The native app (see README.md, "Native screens"). core:* is shared; each feature:* is a part of the app.
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":feature:wallet")
include(":feature:record")
include(":feature:activity")
include(":feature:account")
include(":feature:insights")
include(":feature:settings")
include(":feature:imports")
include(":feature:signin")
