package com.rudnam.wallet.core.network

import com.rudnam.wallet.core.network.model.NetworkAccountInput
import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkCategory
import com.rudnam.wallet.core.network.model.NetworkCategoryInput
import com.rudnam.wallet.core.network.model.NetworkDeleteMe
import com.rudnam.wallet.core.network.model.NetworkMe
import com.rudnam.wallet.core.network.model.NetworkSettingsInput
import com.rudnam.wallet.core.network.model.NetworkBudgetInput
import com.rudnam.wallet.core.network.model.NetworkBulkChange
import com.rudnam.wallet.core.network.model.NetworkBulkResult
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkReconcileResult
import com.rudnam.wallet.core.network.model.NetworkRecordInput
import com.rudnam.wallet.core.network.model.NetworkStats
import com.rudnam.wallet.core.network.model.NetworkTag
import com.rudnam.wallet.core.network.model.NetworkTransaction
import com.rudnam.wallet.core.network.model.NetworkWallet
import java.time.Instant

/**
 * The API, as the data layer sees it. An interface, so tests can answer
 * without a server. Every call throws [java.io.IOException] when the
 * server can't be reached, [SignedOutException] when the session has
 * ended, and [ApiException] for any other refusal.
 */
interface WalletNetworkDataSource {
    suspend fun wallet(): NetworkWallet

    /** Records in [from, to), newest first. */
    suspend fun transactions(from: Instant, to: Instant): List<NetworkTransaction>

    suspend fun budgets(): List<NetworkBudget>

    suspend fun createBudget(input: NetworkBudgetInput): NetworkBudget

    suspend fun updateBudget(id: Long, input: NetworkBudgetInput): NetworkBudget

    suspend fun deleteBudget(id: Long)

    /**
     * Confirmed income and expenses in [from, to), days counted in the IANA
     * time zone [zone]; with [accountId], that account's only.
     */
    suspend fun stats(from: Instant, to: Instant, zone: String, accountId: Long? = null): NetworkStats

    /** Payment notifications the phone has sent (for the first-run checklist). */
    suspend fun notifications(): NetworkNotifications

    /** The APK the server offers, if any. Needs no session. */
    suspend fun downloads(): NetworkDownloads

    /** Confirmed records since [from], newest first: what the user usually does at each shop. */
    suspend fun confirmedSince(from: Instant): List<NetworkTransaction>

    /** Adds a record. With [NetworkRecordInput.clientId], sending it again answers with the record saved the first time. */
    suspend fun createRecord(input: NetworkRecordInput): NetworkTransaction

    suspend fun updateRecord(id: Long, input: NetworkRecordInput): NetworkTransaction

    /** Deletes a record; it can be restored for a day. */
    suspend fun deleteRecord(id: Long)

    suspend fun restoreRecords(ids: List<Long>)

    /** The tags on the user's records, the most used first. */
    suspend fun tags(): List<NetworkTag>

    /**
     * Records matching a search: in [from, to) if given, else every month;
     * with [status] "review", the ones waiting for a check. At most 2000,
     * the newest first.
     */
    suspend fun search(
        from: Instant? = null, to: Instant? = null, accountId: Long? = null, categoryId: String? = null,
        query: String? = null, tag: String? = null, status: String? = null,
    ): List<NetworkTransaction>

    suspend fun bulk(change: NetworkBulkChange): NetworkBulkResult

    /** What an account really holds (cash counted): the difference becomes one record, or none if it matches. */
    suspend fun reconcile(accountId: Long, balance: Long): NetworkReconcileResult

    /** The accounts in a new order, the first first. */
    suspend fun setAccountOrder(ids: List<Long>)

    /** Who is signed in. */
    suspend fun me(): NetworkMe

    /** Ends the session on the server. */
    suspend fun logout()

    /** Deletes the account and everything in it, confirmed with the password or (Google-only) the email. */
    suspend fun deleteMe(confirm: NetworkDeleteMe)

    suspend fun updateSettings(input: NetworkSettingsInput)

    suspend fun createAccount(input: NetworkAccountInput)

    suspend fun updateAccount(id: Long, input: NetworkAccountInput)

    /** Deletes an account and every record in it. */
    suspend fun deleteAccount(id: Long)

    /** The default categories the user doesn't have (yet, or any more). */
    suspend fun missingCategories(): List<NetworkCategory>

    suspend fun createCategory(input: NetworkCategoryInput)

    suspend fun updateCategory(id: Long, input: NetworkCategoryInput)

    /** Deletes a category: its records become uncategorised, and its budgets go. */
    suspend fun deleteCategory(id: Long)
}

/** The server ended the session (401), or nobody is signed in. */
class SignedOutException : java.io.IOException("Not signed in")

/** The server refused a request: [status], and [message], which is meant for the user. */
class ApiException(val status: Int, override val message: String) : Exception(message)
