package com.rudnam.wallet.core.data.widget

import android.content.Context
import androidx.core.content.edit
import com.rudnam.wallet.core.common.Dispatcher
import com.rudnam.wallet.core.common.WalletDispatchers
import com.rudnam.wallet.core.datastore.SessionStore
import com.rudnam.wallet.core.network.SignedOutException
import com.rudnam.wallet.core.network.WalletNetworkDataSource
import com.rudnam.wallet.core.model.WidgetBudget
import com.rudnam.wallet.core.model.WidgetSummary
import com.rudnam.wallet.core.network.model.NetworkWidgetBudget
import com.rudnam.wallet.core.network.model.NetworkWidgetSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** What the widgets can show. */
sealed interface WidgetData {
    /** This month's summary: just fetched, or (offline) the last one. */
    data class Summary(val summary: WidgetSummary) : WidgetData

    data object SignedOut : WidgetData

    /** Offline, with nothing saved. */
    data object Offline : WidgetData
}

/**
 * This month's summary for the home screen widgets (GET /api/widget), kept
 * for when the phone is offline, where the Java widgets kept it ("widgets").
 */
@Singleton
class WidgetSummaries @Inject constructor(
    @ApplicationContext context: Context,
    private val network: WalletNetworkDataSource,
    private val session: SessionStore,
    private val clock: Clock,
    @Dispatcher(WalletDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The last summary fetched, for drawing at once (a resize). */
    fun saved(): WidgetSummary? =
        prefs.getString(SUMMARY, null)?.let { runCatching { json.decodeFromString<NetworkWidgetSummary>(it) }.getOrNull() }?.asModel()

    suspend fun fetch(): WidgetData = withContext(io) {
        if (session.currentToken() == null) return@withContext forget()
        val month = YearMonth.now(clock)
        try {
            val summary = network.widgetSummary(
                month.atDay(1).atStartOfDay(clock.zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant(),
            )
            prefs.edit { putString(SUMMARY, json.encodeToString(summary)) }
            WidgetData.Summary(summary.asModel())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            forget()
        } catch (e: Exception) {
            saved()?.let { WidgetData.Summary(it) } ?: WidgetData.Offline
        }
    }

    private fun forget(): WidgetData {
        prefs.edit { remove(SUMMARY) }
        return WidgetData.SignedOut
    }

    private companion object {
        const val SUMMARY = "summary"
    }
}

private fun NetworkWidgetBudget.asModel() = WidgetBudget(name, amount, spent)

private fun NetworkWidgetSummary.asModel() = WidgetSummary(currency, currencyExponent, spent, balance, budget?.asModel(), budgets.map { it.asModel() }, reviewCount)
