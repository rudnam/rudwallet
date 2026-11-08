package com.rudnam.wallet.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rudnam.wallet.core.data.repository.InsightsRepository
import com.rudnam.wallet.core.data.repository.RecordEvents
import com.rudnam.wallet.core.data.repository.StatsQuery
import com.rudnam.wallet.core.data.repository.StatsResult
import com.rudnam.wallet.core.data.repository.WalletRepository
import com.rudnam.wallet.core.model.Budget
import com.rudnam.wallet.core.model.Stats
import com.rudnam.wallet.core.model.Wallet
import com.rudnam.wallet.feature.insights.navigation.InsightsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** Why the figures shown may be old, or missing. */
sealed interface InsightsProblem {
    data object Offline : InsightsProblem

    /** The server's message for the user, or empty. */
    data class Failed(val message: String) : InsightsProblem
}

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(
        val wallet: Wallet,
        val period: Period,
        /** Whose records count: one account's, or (null) every account's. */
        val accountId: Long?,
        val today: LocalDate,
        /** The period's figures; null while they load the first time. */
        val stats: Stats?,
        val trend: Trend?,
        /** The month still going, across all accounts: where it's heading. */
        val ahead: MonthAhead?,
        val series: Series?,
        val refreshing: Boolean,
        val problem: InsightsProblem?,
    ) : InsightsUiState {
        /** Days of the period so far. */
        val days: Int get() = period.elapsedDays(today)

        val ongoing: Boolean get() = period.ongoing(today)

        /** The next period hasn't begun. */
        val atLatest: Boolean get() = period.reachesToday(today)
    }
}

/** What Insights' controls do. */
interface InsightsActions {
    fun setSpan(span: Span)

    /** The previous ([steps] −1) or next (1) week, month, year or range. */
    fun step(steps: Int)

    /** A range from [first] to [last], both included. */
    fun setRange(first: LocalDate, last: LocalDate)

    fun setAccount(accountId: Long?)

    fun refresh()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val wallet: WalletRepository,
    private val insights: InsightsRepository,
    recordEvents: RecordEvents,
    private val clock: Clock,
) : ViewModel(), InsightsActions {
    private data class Selection(val period: Period, val accountId: Long?)

    private data class Loaded(
        val selection: Selection,
        val stats: Stats?,
        val past: List<Stats>?,
        val problem: InsightsProblem?,
        val refreshing: Boolean,
    )

    private val today: LocalDate get() = LocalDate.now(clock)

    // What's chosen survives the process going away: the route gives the first choice.
    private val selection = MutableStateFlow(
        savedState.get<String>(KEY_SPAN)?.let { span ->
            Selection(Period.fromParams(span, savedState[KEY_START], savedState[KEY_END], today), savedState.get<Long>(KEY_ACCOUNT)?.takeIf { it > 0 })
        } ?: savedState.toRoute<InsightsRoute>().let { r -> Selection(Period.fromParams(r.span, r.start, r.end, today), r.account) },
    )

    /** Asking again: after a pull to refresh (true: the user asked) or a change to the records. */
    private val reloads = MutableStateFlow(Reload(0, userAsked = false))

    private data class Reload(val count: Int, val userAsked: Boolean)

    /** The last pull to refresh answered ([Reload.count]). */
    private var answeredPull = 0

    // A pull shows the spinner once. The last Reload stays in [reloads], and loading runs again
    // with it when the period changes and when the page comes back (WhileSubscribed): those
    // aren't the user asking, so they load quietly.
    private val loaded: Flow<Loaded> = combine(selection, reloads, ::Pair).flatMapLatest { (sel, reload) ->
        val asked = reload.userAsked && reload.count != answeredPull
        if (asked) answeredPull = reload.count
        load(sel, asked)
    }

    val uiState: StateFlow<InsightsUiState> = combine(wallet.wallet, wallet.budgets, loaded) { w, budgets, l ->
        if (w == null) InsightsUiState.Loading else ready(w, budgets, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState.Loading)

    init {
        // A record added, changed or deleted here changes the figures.
        viewModelScope.launch { recordEvents.all.collect { reloads.update { Reload(it.count + 1, userAsked = false) } } }
        // The wallet (categories, accounts) and budgets, if nothing else has fetched them yet.
        viewModelScope.launch { refreshWallet() }
    }

    private fun ready(w: Wallet, budgets: List<Budget>, l: Loaded): InsightsUiState.Ready {
        val period = l.selection.period
        val now = today
        val days = period.elapsedDays(now)
        val stats = l.stats
        return InsightsUiState.Ready(
            wallet = w,
            period = period,
            accountId = l.selection.accountId,
            today = now,
            stats = stats,
            // Categories that moved are main categories, their subcategories counted in.
            trend = if (stats != null && l.past != null && days > 0) trend(stats.byMainCategory(w), l.past.map { it.byMainCategory(w) }) else null,
            ahead = if (stats != null && period.span == Span.Month && period.ongoing(now) && l.selection.accountId == null) {
                monthAhead(stats, budgets, period, now, w)
            } else {
                null
            },
            series = stats?.let { series(period, it) },
            refreshing = l.refreshing,
            problem = l.problem,
        )
    }

    private fun queries(sel: Selection): List<StatsQuery> =
        (listOf(sel.period) + sel.period.comparedWith(today)).map { it.query(clock.zone, sel.accountId) }

    /** What was seen before at once, then the server's answer; then the periods either side, so stepping is instant. */
    private fun load(sel: Selection, userAsked: Boolean): Flow<Loaded> = flow {
        val queries = queries(sel)
        val seen = queries.map(insights::seen)
        val shown = if (seen.all { it != null }) seen.filterNotNull() else null
        emit(Loaded(sel, shown?.first(), shown?.drop(1), problem = null, refreshing = userAsked))
        if (userAsked) refreshWallet()

        val results = coroutineScope { queries.map { async { insights.stats(it) } }.awaitAll() }
        val done = results.filterIsInstance<StatsResult.Done>().map { it.stats }
        if (done.size == results.size) {
            emit(Loaded(sel, done.first(), done.drop(1), problem = null, refreshing = false))
        } else {
            val problem = when (val failure = results.first { it !is StatsResult.Done }) {
                is StatsResult.Failed -> InsightsProblem.Failed(failure.message)
                StatsResult.SignedOut -> null // the app shows sign-in
                else -> InsightsProblem.Offline
            }
            emit(Loaded(sel, shown?.first(), shown?.drop(1), problem, refreshing = false))
            return@flow
        }

        val neighbours = listOf(sel.period.shift(-1), sel.period.shift(1)).filter { it.started(today) }
        for (q in neighbours.flatMap { queries(sel.copy(period = it)) }) {
            if (insights.seen(q) == null) insights.stats(q)
        }
    }

    private suspend fun refreshWallet() {
        val month = YearMonth.now(clock)
        wallet.refresh(month.atDay(1).atStartOfDay(clock.zone).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(clock.zone).toInstant())
    }

    private fun choose(period: Period, accountId: Long? = selection.value.accountId) {
        selection.value = Selection(period, accountId)
        savedState[KEY_SPAN] = period.span.key
        savedState[KEY_START] = period.from.toString()
        savedState[KEY_END] = period.last.toString()
        savedState[KEY_ACCOUNT] = accountId ?: 0L
    }

    override fun setSpan(span: Span) {
        val period = selection.value.period
        if (span == period.span) return
        // Keep looking at today if it's in view, else where the period starts.
        val now = today
        val anchor = if (!now.isBefore(period.from) && now.isBefore(period.to)) now else period.from
        choose(if (span == Span.Range) period.copy(span = Span.Range) else Period.at(span, anchor))
    }

    override fun step(steps: Int) {
        val next = selection.value.period.shift(steps)
        if (next.started(today)) choose(next)
    }

    override fun setRange(first: LocalDate, last: LocalDate) = choose(Period.range(first, last))

    override fun setAccount(accountId: Long?) = choose(selection.value.period, accountId)

    override fun refresh() = reloads.update { Reload(it.count + 1, userAsked = true) }

    private companion object {
        const val KEY_SPAN = "insights.span"
        const val KEY_START = "insights.start"
        const val KEY_END = "insights.end"
        const val KEY_ACCOUNT = "insights.account"
    }
}
