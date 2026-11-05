package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.dao.TransactionDao
import com.rudnam.wallet.core.database.dao.WalletDao
import com.rudnam.wallet.core.database.model.asEntity
import com.rudnam.wallet.core.model.Transaction
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** How counting an account went. */
sealed interface CountResult {
    /** The difference was recorded as [record]. */
    data class Recorded(val record: Transaction) : CountResult

    /** The balance was already right: nothing was recorded. */
    data object AlreadyRight : CountResult

    data object Offline : CountResult

    data class Refused(val message: String) : CountResult

    data object SignedOut : CountResult
}

/** Changes to accounts made outside Settings: counting one, and the order of the card stack. */
interface AccountRepository {
    /** What [accountId] really holds (cash counted): the difference from the app's balance becomes a record. */
    suspend fun count(accountId: Long, balance: Long): CountResult

    /**
     * Puts the accounts in this order, the first first: on the phone at once,
     * then on the server. False if the server didn't take it (the phone's
     * order goes back with the next refresh).
     */
    suspend fun setOrder(ids: List<Long>): Boolean
}

internal class ServerAccountRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val walletDao: WalletDao,
    private val transactionDao: TransactionDao,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : AccountRepository {
    override suspend fun count(accountId: Long, balance: Long): CountResult = withContext(io) {
        try {
            val record = network.reconcile(accountId, balance).transaction?.asModel()
            if (record == null) {
                CountResult.AlreadyRight
            } else {
                transactionDao.upsert(listOf(record.asEntity()))
                CountResult.Recorded(record)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            CountResult.SignedOut
        } catch (e: IOException) {
            CountResult.Offline
        } catch (e: ApiException) {
            if (e.status in 400..499) CountResult.Refused(e.message) else CountResult.Offline
        }
    }

    override suspend fun setOrder(ids: List<Long>): Boolean = withContext(io) {
        walletDao.reorderAccounts(ids)
        try {
            network.setAccountOrder(ids)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
