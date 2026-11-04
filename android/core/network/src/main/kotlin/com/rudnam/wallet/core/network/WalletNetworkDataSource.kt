package com.rudnam.wallet.core.network

import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import java.time.Instant

/**
 * The API, as the data layer sees it. An interface, so tests can answer
 * without a server. Every call throws [java.io.IOException] when the
 * server can't be reached, [SignedOutException] when the session has
 * ended, and [retrofit2.HttpException] for any other refusal.
 */
interface WalletNetworkDataSource {
    suspend fun wallet(): NetworkWallet

    /** Records in [from, to), newest first. */
    suspend fun transactions(from: Instant, to: Instant): List<NetworkTransaction>

    suspend fun budgets(): List<NetworkBudget>

    /** Payment notifications the phone has sent (for the first-run checklist). */
    suspend fun notifications(): NetworkNotifications

    /** The APK the server offers, if any. Needs no session. */
    suspend fun downloads(): NetworkDownloads
}

/** The server ended the session (401), or nobody is signed in. */
class SignedOutException : java.io.IOException("Not signed in")
