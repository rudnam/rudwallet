package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.database.model.asModel
import com.rudnam.wallet.core.model.BulkChange
import com.rudnam.wallet.core.model.BulkResult
import com.rudnam.wallet.core.model.RecordFilter
import com.rudnam.wallet.core.model.Tag
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkBulkChange
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** The most records the server sends at once (maxList in internal/wallet). */
private const val MAX_RECORDS = 2000

internal class OfflineFirstActivityRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val transactionDao: TransactionDao,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : ActivityRepository {
    override val toReview: Flow<List<Transaction>> = transactionDao.observeReview().map { list -> list.map { it.asModel() } }

    override suspend fun refreshReview(): SyncResult = withContext(io) {
        try {
            transactionDao.replaceReview(network.search(status = "review").map { it.asModel().asEntity() })
            SyncResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            SyncResult.SignedOut
        } catch (e: IOException) {
            SyncResult.Offline
        } catch (e: ApiException) {
            SyncResult.Failed
        } catch (e: SerializationException) {
            SyncResult.Failed
        }
    }

    override suspend fun search(filter: RecordFilter): SearchResult = withContext(io) {
        try {
            val found = network.search(
                accountId = filter.accountId,
                categoryId = filter.categoryId?.let { if (it == RecordFilter.NO_CATEGORY) "none" else it.toString() },
                query = filter.query.trim().ifEmpty { null },
                tag = filter.tag.ifEmpty { null },
                withSubcategories = filter.categoryId != null && filter.categoryId != RecordFilter.NO_CATEGORY,
            ).map { it.asModel() }
            SearchResult.Found(found, capped = found.size >= MAX_RECORDS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SearchResult.Offline
        } catch (e: ApiException) {
            SearchResult.Failed
        } catch (e: SerializationException) {
            SearchResult.Failed
        }
    }

    override suspend fun bulk(change: BulkChange): BulkOutcome = attempt {
        val result = network.bulk(change.asNetwork())
        // Gone from the lists at once; any other change shows with the next refresh.
        if (change is BulkChange.Delete) transactionDao.deleteAll(change.ids)
        BulkResult(result.changed, result.skipped)
    }

    override suspend fun restore(ids: List<Long>): BulkOutcome = attempt {
        network.restoreRecords(ids)
        BulkResult(ids.size, 0)
    }

    override suspend fun setAside(): SetAsideResult = withContext(io) {
        try {
            SetAsideResult.Found(network.setAside().duplicates.map { it.asModel() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SetAsideResult.Offline
        } catch (e: ApiException) {
            SetAsideResult.Failed
        } catch (e: SerializationException) {
            SetAsideResult.Failed
        }
    }

    override suspend fun tags(): List<Tag> = withContext(io) {
        runCatching { network.tags().map { Tag(it.name, it.count) } }.getOrElse {
            // Offline: the tags on the records the phone has.
            transactionDao.since(Instant.EPOCH).flatMap { it.tags }
                .groupingBy { it.lowercase() }.eachCount()
                .map { (name, count) -> Tag(name, count) }
                .sortedByDescending { it.count }
        }
    }

    override suspend fun match(id: Long, near: Instant): Transaction? = withContext(io) {
        transactionDao.get(id)?.asModel() ?: runCatching {
            val day = Duration.ofDays(2)
            network.search(from = near.minus(day), to = near.plus(day)).map { it.asModel() }.firstOrNull { it.id == id }
        }.getOrNull()
    }

    private suspend fun attempt(block: suspend () -> BulkResult): BulkOutcome = withContext(io) {
        try {
            BulkOutcome.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            BulkOutcome.SignedOut
        } catch (e: IOException) {
            BulkOutcome.Offline
        } catch (e: ApiException) {
            if (e.status in 400..499) BulkOutcome.Refused(e.message) else BulkOutcome.Offline
        }
    }

    private fun BulkChange.asNetwork() = when (this) {
        is BulkChange.Delete -> NetworkBulkChange(ids, "delete")
        is BulkChange.Categorize -> NetworkBulkChange(ids, "categorize", categoryId = categoryId)
        is BulkChange.Move -> NetworkBulkChange(ids, "move", accountId = accountId)
        is BulkChange.Confirm -> NetworkBulkChange(ids, "confirm")
        is BulkChange.AddTag -> NetworkBulkChange(ids, "tag", tag = tag)
        is BulkChange.RemoveTag -> NetworkBulkChange(ids, "untag", tag = tag)
    }
}
