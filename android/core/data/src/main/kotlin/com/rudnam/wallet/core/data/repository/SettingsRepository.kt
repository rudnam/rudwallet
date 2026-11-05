package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.dao.BudgetDao
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.dao.WalletDao
import com.rudnam.wallet.core.database.model.WalletEntity
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.model.AccountInput
import com.rudnam.wallet.core.model.Category
import com.rudnam.wallet.core.model.CategoryInput
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.NetworkSettingsInput
import com.rudnam.wallet.core.network.model.asModel
import com.rudnam.wallet.core.network.model.asNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Changes to the wallet's settings, accounts and categories. They're made
 * on the server (balances and names are the server's to work out), then the
 * phone's copy of the wallet is fetched again, so every screen shows them.
 */
interface SettingsRepository {
    /** The wallet's main currency: only until the first record. */
    suspend fun setCurrency(currency: String): ChangeResult

    /** Adds an account ([id] null) or changes one. */
    suspend fun saveAccount(id: Long?, input: AccountInput): ChangeResult

    /** Deletes an account and every record in it. */
    suspend fun deleteAccount(id: Long): ChangeResult

    /** The default categories the user doesn't have; none offline. */
    suspend fun missingCategories(): List<Category>

    /** Adds a category ([id] null) or changes one. */
    suspend fun saveCategory(id: Long?, input: CategoryInput): ChangeResult

    /** Deletes a category: its records become uncategorised, and its budgets go. */
    suspend fun deleteCategory(id: Long): ChangeResult
}

internal class ServerSettingsRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    private val budgetDao: BudgetDao,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : SettingsRepository {
    override suspend fun setCurrency(currency: String) = change(io) {
        val settings = network.wallet().settings
        network.updateSettings(NetworkSettingsInput(currency, settings.autoConfirm, settings.defaultAccountId))
        reload()
    }

    override suspend fun saveAccount(id: Long?, input: AccountInput) = change(io) {
        if (id == null) network.createAccount(input.asNetwork()) else network.updateAccount(id, input.asNetwork())
        reload()
    }

    override suspend fun deleteAccount(id: Long) = change(io) {
        network.deleteAccount(id)
        transactionDao.deleteForAccount(id)
        reload()
    }

    override suspend fun missingCategories(): List<Category> = withContext(io) {
        try {
            network.missingCategories().map { it.asModel() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun saveCategory(id: Long?, input: CategoryInput) = change(io) {
        if (id == null) network.createCategory(input.asNetwork()) else network.updateCategory(id, input.asNetwork())
        reload()
    }

    override suspend fun deleteCategory(id: Long) = change(io) {
        network.deleteCategory(id)
        transactionDao.uncategorize(id)
        budgetDao.deleteForCategory(id)
        reload()
    }

    // The change is made; if fetching it back fails, the next refresh brings it.
    private suspend fun reload() {
        runCatching { walletDao.save(network.wallet().asModel()) }.onFailure { if (it is CancellationException) throw it }
    }
}

/** Replaces the phone's settings, accounts and categories with [wallet]'s. */
internal suspend fun WalletDao.save(wallet: Wallet) = replaceWallet(
    WalletEntity(
        currency = wallet.settings.currency,
        currencyExponent = wallet.settings.currencyExponent,
        autoConfirm = wallet.settings.autoConfirm,
        defaultAccountId = wallet.settings.defaultAccountId,
        reviewCount = wallet.reviewCount,
    ),
    wallet.accounts.mapIndexed { i, a -> a.asEntity(i) },
    wallet.categories.mapIndexed { i, c -> c.asEntity(i) },
)
