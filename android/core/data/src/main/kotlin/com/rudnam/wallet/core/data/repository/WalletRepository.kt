package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.Wallet
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** How a refresh went. */
enum class SyncResult {
    Done,

    /** The server couldn't be reached: what's on the phone stays. */
    Offline,

    /** The session has ended. */
    SignedOut,

    /** The server answered with an error. */
    Failed,
}

/**
 * The wallet, offline first: the flows read the phone's database, which
 * [refresh] brings up to date from the server. A screen shows what's on
 * the phone at once, and the server's answer when it comes.
 */
interface WalletRepository {
    /** Settings, accounts, categories and the review count; null until the first refresh. */
    val wallet: Flow<Wallet?>

    val budgets: Flow<List<Budget>>

    /** Records in [from, to), newest first. */
    fun transactions(from: Instant, to: Instant): Flow<List<Transaction>>

    /** Fetches the wallet, the budgets and the records in [from, to). */
    suspend fun refresh(from: Instant, to: Instant): SyncResult

    /** Whether the phone has ever sent a payment notification (Wallet's first-run checklist). */
    suspend fun hasSentNotifications(): Boolean?

    /** Forgets everything kept on the phone, when the user signs out. */
    suspend fun forget()
}
