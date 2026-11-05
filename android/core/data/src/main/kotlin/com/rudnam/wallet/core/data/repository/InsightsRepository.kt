package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.database.dao.BudgetDao
import com.rudnam.wallet.core.database.model.asEntity

import com.rudnam.wallet.core.model.BudgetInput
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.network.ApiException
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asModel
import com.rudnam.wallet.core.network.model.asNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Which stats: confirmed records in [from, to), days counted in [zone], of one account or (null) all. */
data class StatsQuery(val from: Instant, val to: Instant, val zone: ZoneId, val accountId: Long? = null)

/** How asking the server went. */
sealed interface StatsResult {
    data class Done(val stats: Stats) : StatsResult

    data object Offline : StatsResult

    data object SignedOut : StatsResult

    /** The server refused or answered something unreadable; [message] is for the user, if it gave one. */
    data class Failed(val message: String) : StatsResult
}

/** How saving or deleting a budget went. */
sealed interface BudgetResult {
    data object Saved : BudgetResult

    data object Offline : BudgetResult

    data object SignedOut : BudgetResult

    /** The server said no: [message] says why, for the user. */
    data class Refused(val message: String) : BudgetResult
}

/**
 * Insights' figures and the budgets. Stats are totals the server works out
 * over any period, so they aren't kept in the database: the answers seen
 * since the app started are kept in memory, to show at once while they're
 * asked for again. Budgets are kept in the database (WalletRepository.budgets).
 */
interface InsightsRepository {
    /** The last answer for [query] since the app started, or null. */
    fun seen(query: StatsQuery): Stats?

    suspend fun stats(query: StatsQuery): StatsResult

    suspend fun createBudget(input: BudgetInput): BudgetResult

    suspend fun updateBudget(id: Long, input: BudgetInput): BudgetResult

    suspend fun deleteBudget(id: Long): BudgetResult
}

/** Stats seen since the app started, the most recent few; emptied when someone signs out. */
@Singleton
class StatsCache @Inject constructor() {
    private val entries = object : LinkedHashMap<StatsQuery, Stats>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StatsQuery, Stats>) = size > 96
    }

    @Synchronized
    operator fun get(query: StatsQuery): Stats? = entries[query]

    @Synchronized
    operator fun set(query: StatsQuery, stats: Stats) {
        entries[query] = stats
    }

    @Synchronized
    fun clear() = entries.clear()
}

internal class ServerInsightsRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val budgetDao: BudgetDao,
    private val cache: StatsCache,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : InsightsRepository {
    override fun seen(query: StatsQuery): Stats? = cache[query]

    override suspend fun stats(query: StatsQuery): StatsResult = withContext(io) {
        try {
            val stats = network.stats(query.from, query.to, query.zone.id, query.accountId).asModel()
            cache[query] = stats
            StatsResult.Done(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            StatsResult.SignedOut
        } catch (e: IOException) {
            StatsResult.Offline
        } catch (e: ApiException) {
            StatsResult.Failed(e.message)
        } catch (e: SerializationException) {
            StatsResult.Failed("")
        } catch (e: IllegalArgumentException) {
            StatsResult.Failed("") // e.g. a day written in a way it never has been
        }
    }

    override suspend fun createBudget(input: BudgetInput) = save { budgetDao.upsert(network.createBudget(input.asNetwork()).asModel().asEntity(0)) }

    override suspend fun updateBudget(id: Long, input: BudgetInput) =
        save { budgetDao.upsert(network.updateBudget(id, input.asNetwork()).asModel().asEntity(0)) }

    override suspend fun deleteBudget(id: Long) = save {
        try {
            network.deleteBudget(id)
        } catch (e: ApiException) {
            // Deleted already (on the website, say): what the user wanted.
            if (e.status != 404) throw e
        }
        budgetDao.delete(id)
    }

    private suspend fun save(block: suspend () -> Unit): BudgetResult = withContext(io) {
        try {
            block()
            BudgetResult.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            BudgetResult.SignedOut
        } catch (e: IOException) {
            BudgetResult.Offline
        } catch (e: ApiException) {
            if (e.status in 400..499) BudgetResult.Refused(e.message) else BudgetResult.Offline
        }
    }
}

