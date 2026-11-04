package com.rudnam.wallet.core.network

import com.rudnam.wallet.core.network.model.NetworkBudget
import com.rudnam.wallet.core.network.model.NetworkDownloads
import com.rudnam.wallet.core.network.model.NetworkNotifications
import com.rudnam.wallet.core.network.model.NetworkRecordInput
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
}

/** The server ended the session (401), or nobody is signed in. */
class SignedOutException : java.io.IOException("Not signed in")

/** The server refused a request: [status], and [message], which is meant for the user. */
class ApiException(val status: Int, override val message: String) : Exception(message)
