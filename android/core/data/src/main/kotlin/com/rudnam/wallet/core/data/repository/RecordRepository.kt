package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.Transaction

/** How adding or changing a record went. */
sealed interface SaveResult {
    /** The server has it. */
    data class Saved(val record: Transaction) : SaveResult

    /** Done (a delete, a restore). */
    data object Done : SaveResult

    /** A record not sent yet was sent while it was being changed: it's the server's now, to open again. */
    data object SentMeanwhile : SaveResult

    /** No connection: it's kept on the phone and sent as soon as there is one. */
    data object Queued : SaveResult

    /** The server refused it; [message] is meant for the user. */
    data class Refused(val message: String) : SaveResult

    /** This needs the server, which can't be reached (changing or deleting a record it has). */
    data object Offline : SaveResult

    data object SignedOut : SaveResult
}

/** What the user usually does: for the record sheet's suggestions. */
data class RecordHabits(
    /** Recent payees, the most recent first, with what was chosen there last. */
    val payees: Map<String, Transaction>,
    /** How many recent records each category has, to show the most used first. */
    val categoryUse: Map<Long, Int>,
    /** The tags in use, the most used first. */
    val tags: List<String>,
)

/**
 * Adding, changing and deleting records. A new record without a connection
 * goes to the outbox and is sent by OutboxWorker when there is one; the
 * lists show it meanwhile (WalletRepository). Changing or deleting a record
 * the server has needs the server, as on the web.
 */
interface RecordRepository {
    suspend fun add(input: RecordInput): SaveResult

    /** Changes a record; one not sent yet (an id below zero) changes on the phone. */
    suspend fun update(id: Long, input: RecordInput): SaveResult

    /** Deletes a record; one not sent yet is gone at once, with nothing to restore. */
    suspend fun delete(id: Long): SaveResult

    /** Brings back a record deleted in the last day. */
    suspend fun restore(id: Long): SaveResult

    /** The record with this id: from the phone's copy, or what's waiting to be sent. */
    suspend fun get(id: Long): Transaction?

    suspend fun habits(): RecordHabits

    /** The account a new record starts in: the one used last time. */
    suspend fun lastAccount(): Long?

    /**
     * Sends what's waiting, oldest first. [SyncResult.Offline] (or
     * SignedOut): stopped, try again later; [SyncResult.Done]: nothing left
     * that can go (a refused one waits for the user).
     */
    suspend fun sendPending(): SyncResult
}
