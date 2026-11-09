package com.rudnam.wallet.core.network

import javax.inject.Inject
import javax.inject.Singleton

/** The server the app talks to (rudwallet.server in android/gradle.properties). */
@Singleton
class Server @Inject constructor() {
    val base: String = BuildConfig.SERVER

    /** A path the server gave, e.g. /download/wallet.apk, as a full address. */
    fun resolve(path: String): String = if (path.startsWith("http")) path else base + path
}
