package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.model.RecurringInput
import com.rudnam.wallet.core.model.RecurringOverview
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.network.model.asModel
import com.rudnam.wallet.core.network.model.asNetwork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import javax.inject.Inject

/**
 * Recurring payments: subscriptions, bills and income the server finds in
 * the records and works out (their payments, next due date, what's still to
 * come this month). Like stats, the answer is worked out from every record
 * each time, so it isn't kept in the database: the last one is kept in
 * memory ([StatsCache.recurring]), to show at once while it's asked again.
 */
interface RecurringRepository {
    /** The last answer since the app started, or null; it changes as the server answers. */
    val overview: StateFlow<RecurringOverview?>

    /** Asks the server again, with dates in [zone]. */
    suspend fun refresh(zone: ZoneId): Fetched<RecurringOverview>

    suspend fun create(input: RecurringInput): ChangeResult

    suspend fun update(id: Long, input: RecurringInput): ChangeResult

    suspend fun delete(id: Long): ChangeResult

    /** Says a suggestion isn't recurring: it isn't made again. */
    suspend fun dismiss(key: String): ChangeResult
}

internal class ServerRecurringRepository @Inject constructor(
    private val network: WalletNetworkDataSource,
    private val cache: StatsCache,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) : RecurringRepository {
    override val overview: StateFlow<RecurringOverview?> get() = cache.recurring

    override suspend fun refresh(zone: ZoneId): Fetched<RecurringOverview> =
        fetch(io) { network.recurring(zone.id).asModel() }.also { if (it is Fetched.Done) cache.recurring.value = it.value }

    override suspend fun create(input: RecurringInput) = change(io) { network.createRecurring(input.asNetwork()) }

    override suspend fun update(id: Long, input: RecurringInput) = change(io) { network.updateRecurring(id, input.asNetwork()) }

    override suspend fun delete(id: Long) = change(io) {
        try {
            network.deleteRecurring(id)
        } catch (e: com.rudnam.wallet.core.network.ApiException) {
            // Deleted already (on the website, say): what the user wanted.
            if (e.status != 404) throw e
        }
    }

    override suspend fun dismiss(key: String) = change(io) { network.dismissRecurring(key) }
}
