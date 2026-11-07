package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.BulkResult
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.Tag
import com.rudnam.wallet.core.model.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** A search through every month: what matched, or why there's nothing to show. */
sealed interface SearchResult {
    /** [capped]: the server sends the newest 2000 at most, and there may be more. */
    data class Found(val records: List<Transaction>, val capped: Boolean) : SearchResult

    data object Offline : SearchResult

    data object Failed : SearchResult
}

/** How a change to many records went. */
sealed interface BulkOutcome {
    data class Done(val result: BulkResult) : BulkOutcome

    data object Offline : BulkOutcome

    data class Refused(val message: String) : BulkOutcome

    data object SignedOut : BulkOutcome
}

/**
 * What Activity needs beyond a month's records (WalletRepository): the
 * records waiting for a check, kept on the phone; searching every month;
 * tags; and changing many records at once.
 */
interface ActivityRepository {
    /** Records to review, from any month, newest first. */
    val toReview: Flow<List<Transaction>>

    suspend fun refreshReview(): SyncResult

    /** Every month's records matching [filter] (a search or a tag); needs the server. */
    suspend fun search(filter: RecordFilter): SearchResult

    suspend fun bulk(change: BulkChange): BulkOutcome

    /** Brings back records deleted in the last day. */
    suspend fun restore(ids: List<Long>): BulkOutcome

    /** The tags in use, the most used first. */
    suspend fun tags(): List<Tag>

    /** The record a possible duplicate matches, which is within a day of [near]. */
    suspend fun match(id: Long, near: Instant): Transaction?
}
