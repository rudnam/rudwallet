package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.WalletCache
import com.rudnam.wallet.core.database.dao.BudgetDao
import com.rudnam.wallet.core.database.dao.OutboxDao
import com.rudnam.wallet.core.database.model.asTransaction
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.dao.WalletDao
import com.rudnam.wallet.core.database.model.WalletEntity
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.database.model.asModel
import com.rudnam.wallet.core.database.model.settings
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

internal class OfflineFirstWalletRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    private val budgetDao: BudgetDao,
    private val outboxDao: OutboxDao,
    private val cache: WalletCache,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : WalletRepository {
    /** Records not on the server yet, as they're shown meanwhile. */
    private val pending = outboxDao.observeAll().map { list -> list.map { it.asTransaction() } }

    override val wallet: Flow<Wallet?> = combine(
        walletDao.observeWallet(),
        walletDao.observeAccounts(),
        walletDao.observeCategories(),
        pending,
    ) { w, accounts, categories, waiting ->
        w?.let {
            Wallet(it.settings(), accounts.map { a -> a.asModel() }, categories.map { c -> c.asModel() }, it.reviewCount)
                .withPending(waiting.filter { t -> t.pending == com.rudnam.wallet.core.model.Pending.Waiting })
        }
    }

    override val budgets = budgetDao.observeBudgets().map { list -> list.map { it.asModel() } }

    /** The server's records and, among them by date, those still on their way to it. */
    override fun transactions(from: Instant, to: Instant): Flow<List<Transaction>> =
        combine(transactionDao.observeRange(from, to), pending) { saved, waiting ->
            val here = waiting.filter { it.occurredAt >= from && it.occurredAt < to }
            (saved.map { it.asModel() } + here).sortedWith(compareByDescending<Transaction> { it.occurredAt }.thenByDescending { it.id })
        }

    override suspend fun refresh(from: Instant, to: Instant): SyncResult = sync {
        coroutineScope {
            val wallet = async { network.wallet().asModel() }
            val records = async { network.transactions(from, to).map { it.asModel() } }
            val budgets = async { network.budgets().map { it.asModel() } }
            val w = wallet.await()
            walletDao.replaceWallet(
                WalletEntity(
                    currency = w.settings.currency,
                    currencyExponent = w.settings.currencyExponent,
                    autoConfirm = w.settings.autoConfirm,
                    defaultAccountId = w.settings.defaultAccountId,
                    reviewCount = w.reviewCount,
                ),
                w.accounts.mapIndexed { i, a -> a.asEntity(i) },
                w.categories.mapIndexed { i, c -> c.asEntity(i) },
            )
            transactionDao.replaceRange(from, to, records.await().map { it.asEntity() })
            budgetDao.replaceAll(budgets.await().mapIndexed { i, b -> b.asEntity(i) })
        }
    }

    override suspend fun hasSentNotifications(): Boolean? = withContext(io) {
        try {
            network.notifications().notifications.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun forget() = withContext(io) { cache.clear() }

    private suspend fun sync(block: suspend () -> Unit): SyncResult = withContext(io) {
        try {
            block()
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
        } catch (e: IllegalArgumentException) {
            SyncResult.Failed // e.g. a date the server wrote in a way it never has
        }
    }
}
