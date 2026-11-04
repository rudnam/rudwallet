package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.WalletCache
import com.rudnam.wallet.core.database.dao.BudgetDao
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.dao.WalletDao
import com.rudnam.wallet.core.database.model.WalletEntity
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.database.model.asModel
import com.rudnam.wallet.core.database.model.settings
import com.rudnam.wallet.core.model.Wallet
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
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

internal class OfflineFirstWalletRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    private val budgetDao: BudgetDao,
    private val cache: WalletCache,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : WalletRepository {
    override val wallet: Flow<Wallet?> = combine(
        walletDao.observeWallet(),
        walletDao.observeAccounts(),
        walletDao.observeCategories(),
    ) { w, accounts, categories ->
        w?.let { Wallet(it.settings(), accounts.map { a -> a.asModel() }, categories.map { c -> c.asModel() }, it.reviewCount) }
    }

    override val budgets = budgetDao.observeBudgets().map { list -> list.map { it.asModel() } }

    override fun transactions(from: Instant, to: Instant) =
        transactionDao.observeRange(from, to).map { list -> list.map { it.asModel() } }

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
        } catch (e: HttpException) {
            SyncResult.Failed
        } catch (e: SerializationException) {
            SyncResult.Failed
        } catch (e: IllegalArgumentException) {
            SyncResult.Failed // e.g. a date the server wrote in a way it never has
        }
    }
}
