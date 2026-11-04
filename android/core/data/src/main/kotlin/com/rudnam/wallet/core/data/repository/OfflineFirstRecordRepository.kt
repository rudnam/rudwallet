package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.data.sync.OutboxScheduler
import com.rudnam.wallet.core.database.dao.OutboxDao
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.database.model.asModel
import com.rudnam.wallet.core.database.model.asOutbox
import com.rudnam.wallet.core.database.model.asTransaction
import com.rudnam.wallet.core.database.model.input
import com.rudnam.wallet.core.datastore.UserPreferencesRepository
import com.rudnam.wallet.core.model.RecordInput
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asModel
import com.rudnam.wallet.core.network.model.asNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.inject.Inject

internal class OfflineFirstRecordRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val transactionDao: TransactionDao,
    private val outboxDao: OutboxDao,
    private val preferences: UserPreferencesRepository,
    private val scheduler: OutboxScheduler,
    private val clock: Clock,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : RecordRepository {
    override suspend fun add(input: RecordInput): SaveResult = withContext(io) {
        preferences.setLastAccount(input.accountId)
        val clientId = UUID.randomUUID().toString()
        when (val result = attempt { network.createRecord(input.asNetwork(clientId)).asModel() }) {
            is Attempt.Done -> {
                transactionDao.upsert(listOf(result.value.asEntity()))
                SaveResult.Saved(result.value)
            }
            Attempt.Offline -> {
                // Kept on the phone and sent once there's a connection: the same client_id, so it's added once.
                outboxDao.insert(input.asOutbox(clientId, clock.instant()))
                scheduler.schedule()
                SaveResult.Queued
            }
            is Attempt.Refused -> SaveResult.Refused(result.message)
            Attempt.SignedOut -> SaveResult.SignedOut
        }
    }

    override suspend fun update(id: Long, input: RecordInput): SaveResult = withContext(io) {
        if (id < 0) {
            val changed = outboxDao.update(
                -id, input.kind.apiName, input.amount, input.accountId, input.toAccountId, input.categoryId,
                input.occurredAt, input.payee, input.note, input.status.apiName, input.tags,
            )
            // Gone from the outbox: it was sent meanwhile, and is a record of the server's now.
            if (changed == 0) return@withContext SaveResult.SentMeanwhile
            scheduler.schedule()
            return@withContext SaveResult.Queued
        }
        when (val result = attempt { network.updateRecord(id, input.asNetwork()).asModel() }) {
            is Attempt.Done -> {
                transactionDao.upsert(listOf(result.value.asEntity()))
                SaveResult.Saved(result.value)
            }
            Attempt.Offline -> SaveResult.Offline
            is Attempt.Refused -> SaveResult.Refused(result.message)
            Attempt.SignedOut -> SaveResult.SignedOut
        }
    }

    override suspend fun delete(id: Long): SaveResult = withContext(io) {
        if (id < 0) {
            return@withContext if (outboxDao.delete(-id) > 0) SaveResult.Done else SaveResult.SentMeanwhile
        }
        when (val result = attempt { network.deleteRecord(id) }) {
            is Attempt.Done -> {
                transactionDao.delete(id)
                SaveResult.Done
            }
            Attempt.Offline -> SaveResult.Offline
            is Attempt.Refused -> SaveResult.Refused(result.message)
            Attempt.SignedOut -> SaveResult.SignedOut
        }
    }

    override suspend fun restore(id: Long): SaveResult = withContext(io) {
        when (val result = attempt { network.restoreRecords(listOf(id)) }) {
            // Its place in the lists comes back with the next refresh.
            is Attempt.Done -> SaveResult.Done
            Attempt.Offline -> SaveResult.Offline
            is Attempt.Refused -> SaveResult.Refused(result.message)
            Attempt.SignedOut -> SaveResult.SignedOut
        }
    }

    override suspend fun get(id: Long): Transaction? = withContext(io) {
        if (id < 0) outboxDao.get(-id)?.asTransaction() else transactionDao.get(id)?.asModel()
    }

    override suspend fun habits(): RecordHabits = withContext(io) {
        coroutineScope {
            val since = clock.instant().minus(Duration.ofDays(120))
            val recent = async {
                // Offline: what the phone has.
                runCatching { network.confirmedSince(since).map { it.asModel() } }
                    .getOrElse { transactionDao.since(since).map { it.asModel() }.filter { it.confirmed } }
            }
            val tags = async { runCatching { network.tags().map { it.name } }.getOrDefault(emptyList()) }
            val list = recent.await()
            val payees = LinkedHashMap<String, Transaction>()
            val use = HashMap<Long, Int>()
            for (t in list) {
                if (t.payee.isNotEmpty() && t.payee !in payees) payees[t.payee] = t
                t.categoryId?.let { use.merge(it, 1, Int::plus) }
            }
            RecordHabits(payees, use, tags.await())
        }
    }

    override suspend fun lastAccount(): Long? = preferences.preferences.first().lastAccountId

    override suspend fun sendPending(): SyncResult = withContext(io) {
        for (waiting in outboxDao.all().filter { it.error == null }) {
            when (val result = attempt { network.createRecord(waiting.input().asNetwork(waiting.clientId)).asModel() }) {
                is Attempt.Done -> {
                    var saved = result.value
                    // Changed or deleted on the phone while it was on its way.
                    val now = outboxDao.get(waiting.localId)
                    if (now == null) {
                        runCatching { network.deleteRecord(saved.id) }
                    } else {
                        if (now.version != waiting.version) {
                            saved = runCatching { network.updateRecord(saved.id, now.input().asNetwork()).asModel() }.getOrDefault(saved)
                        }
                        transactionDao.upsert(listOf(saved.asEntity()))
                        outboxDao.delete(waiting.localId)
                    }
                }
                // It waits for the user, who can change it and save, or delete it.
                is Attempt.Refused -> outboxDao.refuse(waiting.localId, result.message)
                Attempt.Offline -> return@withContext SyncResult.Offline
                Attempt.SignedOut -> return@withContext SyncResult.SignedOut
            }
        }
        SyncResult.Done
    }


    private sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>
        data object Offline : Attempt<Nothing>
        data object SignedOut : Attempt<Nothing>
        data class Refused(val message: String) : Attempt<Nothing>
    }

    /** One request: a refusal (4xx) has a message for the user; anything else is tried again later. */
    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> = try {
        Attempt.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignedOutException) {
        Attempt.SignedOut
    } catch (e: IOException) {
        Attempt.Offline
    } catch (e: ApiException) {
        if (e.status in 400..499) Attempt.Refused(e.message) else Attempt.Offline
    } catch (e: SerializationException) {
        Attempt.Offline
    }
}
